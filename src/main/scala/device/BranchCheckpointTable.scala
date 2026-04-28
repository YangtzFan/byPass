package mycpu.device

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// BranchCheckpointTable —— 分支 Checkpoint 管理表
// ============================================================================
// 每条被预测的分支指令在 Rename 阶段保存一个 checkpoint，包括：
//   - RAT 完整快照（32 个映射项）
//   - FreeList 的 head/tail 指针快照
//   - ReadyTable 完整快照（128 位）
//
// 当分支预测失败时，使用对应 checkpoint 恢复 RAT、FreeList、ReadyTable
// 最多支持 maxBranchCheckpoints 个同时在飞分支（默认 8）
//
// 分配策略：使用 FIFO 索引轮转分配
// 释放策略：
//   - 分支提交时释放对应 checkpoint
//   - 分支恢复时释放该分支及所有更年轻分支的 checkpoint
//
// Phase A.3：原 save1/save2 / saveValid1/saveValid2 / canSave1/canSave2 /
// saveIdx1/saveIdx2 全部改为 Vec(ckptSaveWidth, _)，内部循环统一处理。
// 当前 ckptSaveWidth=2，行为与旧实现等价；Phase A.4 把 ckptSaveWidth 调到 4
// 即可天然支持每拍最多 4 条预测分支同时下快照。
// ============================================================================
class saveRequestRename extends Bundle {
  // ---- 保存 checkpoint（Rename 阶段，支持每周期最多保存 ckptSaveWidth 个） ----
  // canSave(k) = 1 表示 BCT 至少还有 (k+1) 个空位可用，对应 Rename 同拍有 (k+1)
  // 个被预测分支时的"是否可继续"判定。
  val canSave   = Input(Vec(CPUConfig.ckptSaveWidth, Bool()))
  // saveIdx(k) 是第 (k+1) 个新 checkpoint 的全指针（= tail + k）。
  val saveIdx   = Input(Vec(CPUConfig.ckptSaveWidth, UInt(CPUConfig.ckptPtrWidth.W)))
  // saveValid(k) 由 Rename 驱动：本拍是否真的把第 (k+1) 个 checkpoint 落盘。
  val saveValid = Output(Vec(CPUConfig.ckptSaveWidth, Bool()))
}

class saveData extends Bundle { // checkpoint 保存的数据
  val rat     = Vec(32, UInt(CPUConfig.prfAddrWidth.W))
  val flHead  = UInt((log2Ceil(CPUConfig.freeListEntries) + 1).W)
  val readyTb = Vec(CPUConfig.prfEntries, Bool())
}

class BranchCheckpointTable extends Module {
  val numCkpts = CPUConfig.maxBranchCheckpoints
  val ckptIdxW = CPUConfig.ckptIdxWidth
  val ckptPtrW = CPUConfig.ckptPtrWidth
  val saveW    = CPUConfig.ckptSaveWidth

  val renameRequest = IO(Flipped(new saveRequestRename))
  // saveDataIn(k) 是要落盘的第 (k+1) 份 checkpoint 数据（与 saveValid(k) 一一对应）。
  val saveDataIn = IO(Input(Vec(saveW, new saveData)))

  val io = IO(new Bundle {
    // ---- 恢复 checkpoint（分支预测失败时）----
    val recoverValid  = Input(Bool())
    val recoverIdx    = Input(UInt(ckptPtrW.W))
    // 恢复输出的数据
    val recoverRAT    = Output(Vec(32, UInt(CPUConfig.prfAddrWidth.W)))
    val recoverFLHead = Output(UInt((log2Ceil(CPUConfig.freeListEntries) + 1).W))
    val recoverReady  = Output(Vec(CPUConfig.prfEntries, Bool()))
    // ---- 释放 checkpoint（分支正确提交时）----
    // 多 lane 同拍可能同时提交多条分支，需要按数量一并推进 head 指针
    val freeValid     = Input(Bool())
    val freeCount     = Input(UInt(log2Ceil(CPUConfig.commitWidth + 1).W)) // 本拍实际提交的分支数量（0..commitWidth），用于 head += freeCount
    // ---- Refresh 通告：执行结果写回后，需要把 pdst 同步记录到所有在飞 checkpoint 的
    //      "快照之后已就绪"位向量里，防止分支恢复时用陈旧快照覆盖掉已就绪的寄存器。
    //      多 Refresh lane 时每 lane 一个 (valid, addr) 端口，各自 OR 到 refreshedSinceSnap。
    val refreshValid  = Input(Vec(CPUConfig.refreshWidth, Bool()))
    val refreshAddr   = Input(Vec(CPUConfig.refreshWidth, UInt(CPUConfig.prfAddrWidth.W)))
    // recoverIdx 是否落在合法在飞区间 [head, tail)。
    // MyCPU 顶层用此信号过滤掉越界（stale）的 memRedirect，防止 RAT/FreeList 被污染。
    val recoverIdxInRange = Output(Bool())
  })

  // ---- checkpoint 存储 ----
  val ckPointTable = Reg(Vec(numCkpts, new saveData))

  // ---- 每个 checkpoint slot 独立维护的 “快照之后已就绪” 位向量 ----
  // 语义：refreshedSinceSnap(k)(p) = 1 表示自该 slot 上次被保存以来，物理寄存器 p 已经在 Refresh
  // 阶段写回过结果。若发生分支预测失败，根据 recoverIdx 选出对应 slot，将其 OR 到快照 readyTb
  // 上再输出，避免陈旧快照把“已经就绪”的寄存器重新标记为 busy，从而引发 IQ 中的永久等待。
  // 占用约 numCkpts * prfEntries 个 bit（默认 8*128 = 1024 bit），开销可忽略。
  val refreshedSinceSnap = RegInit(VecInit(Seq.fill(numCkpts)(VecInit(Seq.fill(CPUConfig.prfEntries)(false.B)))))

  // ---- FIFO 分配/释放管理 ----
  val head  = RegInit(0.U((ckptIdxW + 1).W)) // 最老分支的 checkpoint（释放端）
  val tail  = RegInit(0.U((ckptIdxW + 1).W)) // 下一个可分配的 checkpoint（分配端）
  private def idx(ptr: UInt) = ptr(ckptIdxW - 1, 0)
  val count = tail - head

  // canSave(k)：BCT 至少还有 (k+1) 个空位可用 ⇔ count <= numCkpts-(k+1)
  for (k <- 0 until saveW) {
    renameRequest.canSave(k) := count <= (numCkpts - (k + 1)).U
    renameRequest.saveIdx(k) := tail + k.U
  }

  // ---- 保存逻辑 ----
  // saveValid 必须按 lane 单调递减（k 较大需 k-1 也 valid），由上游 Rename 保证。
  // 本拍实际保存的数量 = popcount(saveValid)，tail 推进相同步长。
  val anySave = renameRequest.saveValid.reduce(_ || _)
  val saveCnt = PopCount(renameRequest.saveValid)
  when(anySave) {
    tail := tail + saveCnt
    for (k <- 0 until saveW) {
      when(renameRequest.saveValid(k)) {
        ckPointTable(idx(tail + k.U)) := saveDataIn(k)
      }
    }
  }

  // ---- 每拍维护 refreshedSinceSnap ----
  // 默认：若本拍 Refresh 任一 lane 写回了某个 pdst，则把它记到所有 slot 的 refreshed 向量里。
  for (r <- 0 until CPUConfig.refreshWidth) {
    when(io.refreshValid(r) && io.refreshAddr(r) =/= 0.U) {
      for (s <- 0 until numCkpts) {
        refreshedSinceSnap(s)(io.refreshAddr(r)) := true.B
      }
    }
  }
  // 若本拍正在保存新的 checkpoint，则对应 slot 的 refreshed 向量整体清零，
  // 表示以保存时刻为基准重新开始追踪。
  for (k <- 0 until saveW) {
    when(renameRequest.saveValid(k)) {
      for (p <- 0 until CPUConfig.prfEntries) {
        refreshedSinceSnap(idx(tail + k.U))(p) := false.B
      }
    }
  }
  // 同拍保存 + 同拍 Refresh：snapshot 取的是 Reg 更新前的值，不包含本拍 refresh，
  // 因此清零后必须把本拍的 refresh 重新写回，避免真实"已就绪"位在恢复时丢失。
  for (r <- 0 until CPUConfig.refreshWidth) {
    when(io.refreshValid(r) && io.refreshAddr(r) =/= 0.U) {
      for (k <- 0 until saveW) {
        when(renameRequest.saveValid(k)) {
          refreshedSinceSnap(idx(tail + k.U))(io.refreshAddr(r)) := true.B
        }
      }
    }
  }

  // ---- 恢复逻辑（分支预测失败时）----
  // 恢复时，tail 回退到 recoverIdx + 1（丢弃该分支及所有更年轻分支的 checkpoint）
  //
  // 防御性校验（v15 修复 algo_array_ops_ooo4 死锁）：
  // recoverIdx 必须落在合法的在飞区间 [head, tail) 内。否则视为 stale memRedirect
  // （上游 Memory 的 in.bits 在 memStall 期间被旧指令的 mispredict 信号污染，
  //  或下游已 commit 的分支对应槽位被旧指令 ckptIdx 错误命中）。
  // 若直接接受越界 recoverIdx，tail 会被设到 head 之后的远方，count 立即超过 numCkpts，
  // 8 个物理槽中出现幻影占位，BCT 永久 full → Rename 永久 stall → 处理器死锁。
  // 所以这里把越界 recover 抑制为 no-op，保护 head/tail 不变。
  val recoverDist     = io.recoverIdx - head           // 距 head 的距离（4-bit 模运算）
  val recoverInRange  = recoverDist < count             // 仅当 dist < count 时合法
  val recoverEffective = io.recoverValid && recoverInRange
  io.recoverIdxInRange := recoverInRange
  when(recoverEffective) {
    tail := io.recoverIdx +& 1.U
  }

  // ---- 释放逻辑（分支正确提交时释放最老的一个）----
  // recover 和 free 可以同周期发生：recover 由 Memory redirect 触发，free 由 Commit 触发
  // 提交的分支一定比 mispredict 的分支更老，释放合法，不能抑制
  when(io.freeValid) {
    head := head + io.freeCount // 按本拍实际提交的分支数同时推进，避免多 lane 提交时 BCT 槽泄漏
  }

  // ---- 恢复数据输出（组合读取）----
  val ri = idx(io.recoverIdx) // 从全指针中提取索引位访问表项
  io.recoverRAT    := ckPointTable(ri).rat
  io.recoverFLHead := ckPointTable(ri).flHead
  // 恢复时：原始 readyTb 快照 OR 对应 slot 的 refreshedSinceSnap，
  // 把“比该分支更老、在快照后才真正就绪”的 pdst 也标记为 ready。
  for (p <- 0 until CPUConfig.prfEntries) {
    io.recoverReady(p) := ckPointTable(ri).readyTb(p) || refreshedSinceSnap(ri)(p)
  }
}
