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
// ============================================================================
class saveRequestRename extends Bundle {
  // ---- 保存 checkpoint（Rename 阶段，支持每周期最多保存 2 个） ----
  val canSave1   = Input(Bool())  // canSave1：至少 1 个空位（同组内仅有 1 个被预测分支时使用）
  val canSave2   = Input(Bool())  // canSave2：至少 2 个空位（同组内有 2 个被预测分支时使用）
  val saveIdx1   = Input(UInt(CPUConfig.ckptPtrWidth.W)) // 第一个 checkpoint 全指针
  val saveIdx2   = Input(UInt(CPUConfig.ckptPtrWidth.W)) // 第二个 checkpoint 全指针
  val saveValid1 = Output(Bool()) // 第一个 checkpoint 保存使能
  val saveValid2 = Output(Bool()) // 第二个 checkpoint 保存使能
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

  val renameRequest = IO(Flipped(new saveRequestRename))
  val save1 = IO(Input(new saveData)) // 第一个 checkpoint 保存的数据
  val save2 = IO(Input(new saveData)) // 第二个 checkpoint 保存的数据

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
    val freeCount     = Input(UInt(2.W)) // 本拍实际提交的分支数量（0/1/2），用于 head += freeCount
    // ---- Refresh 通告：执行结果写回后，需要把 pdst 同步记录到所有在飞 checkpoint 的
    //      "快照之后已就绪"位向量里，防止分支恢复时用陈旧快照覆盖掉已就绪的寄存器。
    //      多 Refresh lane 时每 lane 一个 (valid, addr) 端口，各自 OR 到 refreshedSinceSnap。
    val refreshValid  = Input(Vec(CPUConfig.refreshWidth, Bool()))
    val refreshAddr   = Input(Vec(CPUConfig.refreshWidth, UInt(CPUConfig.prfAddrWidth.W)))
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

  renameRequest.canSave1 := count <= (numCkpts - 1).U // canSave1：仅需 1 个空位（有 1 个分支时使用）
  renameRequest.canSave2 := count <= (numCkpts - 2).U // canSave2：需要 2 个空位（有 2 个分支时使用）
  renameRequest.saveIdx1 := tail                      // 第一个 checkpoint 全指针 = 当前 tail
  renameRequest.saveIdx2 := tail + 1.U                // 第二个 checkpoint 全指针 = tail + 1

  // ---- 保存逻辑 ----
  // 同周期内可能保存 1 个或 2 个 checkpoint
  // Rename 阶段已经通过 canSave1/canSave2 确保有足够空位，这里不再重复检查
  when(renameRequest.saveValid1 || renameRequest.saveValid2) { 
    tail := tail + Mux(renameRequest.saveValid2, 2.U, Mux(renameRequest.saveValid1, 1.U, 0.U)) // tail 推进：根据实际保存数量推进
    when(renameRequest.saveValid1) { // 保存第一个 checkpoint（部分 RAT，到第一个分支为止）
      ckPointTable(idx(tail)) := save1
    }
    when(renameRequest.saveValid2) { // 保存第二个 checkpoint（包含到第二个分支为止的 RAT）
      ckPointTable(idx(tail + 1.U)) := save2
    }
  }

  // ---- 每拍维护 refreshedSinceSnap ----
  // 默认：若本拍 Refresh 任一 lane 写回了某个 pdst，则把它记到所有 slot 的 refreshed 向量里。
  for (r <- 0 until CPUConfig.refreshWidth) {
    when(io.refreshValid(r) && io.refreshAddr(r) =/= 0.U) {
      for (k <- 0 until numCkpts) {
        refreshedSinceSnap(k)(io.refreshAddr(r)) := true.B
      }
    }
  }
  // 若本拍正在保存新的 checkpoint，则对应 slot 的 refreshed 向量整体清零，
  // 表示以保存时刻为基准重新开始追踪。
  when(renameRequest.saveValid1) {
    for (p <- 0 until CPUConfig.prfEntries) {
      refreshedSinceSnap(idx(tail))(p) := false.B
    }
  }
  when(renameRequest.saveValid2) {
    for (p <- 0 until CPUConfig.prfEntries) {
      refreshedSinceSnap(idx(tail + 1.U))(p) := false.B
    }
  }
  // 同拍保存 + 同拍 Refresh：snapshot 取的是 Reg 更新前的值，不包含本拍 refresh，
  // 因此清零后必须把本拍的 refresh 重新写回，避免真实"已就绪"位在恢复时丢失。
  for (r <- 0 until CPUConfig.refreshWidth) {
    when(io.refreshValid(r) && io.refreshAddr(r) =/= 0.U) {
      when(renameRequest.saveValid1) {
        refreshedSinceSnap(idx(tail))(io.refreshAddr(r)) := true.B
      }
      when(renameRequest.saveValid2) {
        refreshedSinceSnap(idx(tail + 1.U))(io.refreshAddr(r)) := true.B
      }
    }
  }

  // ---- 恢复逻辑（分支预测失败时）----
  // 恢复时，tail 回退到 recoverIdx + 1（丢弃该分支及所有更年轻分支的 checkpoint）
  when(io.recoverValid) {
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
