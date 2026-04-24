package mycpu.device

import chisel3._
import chisel3.util._
import mycpu.CPUConfig
import mycpu.dataLane._

// ============================================================================
// ROB（重排序缓冲区）—— 支持 4-wide 分配 + Memory 阶段回滚
// ============================================================================
// 变更：
//   - 分配接口由 Dispatch 阶段驱动（原由 Rename 驱动）
//   - 完成标记接口改为 ROBRefreshIO（Refresh 阶段标记完成）
//   - 移除 Store 相关字段（Store 信息由 StoreBuffer 管理）
//   - Commit 接口不再输出 Store 地址/数据/掩码
// ============================================================================
class ROB(val entries: Int = CPUConfig.robEntries) extends Module {
  val idxW = CPUConfig.robIdxWidth  // 索引位宽（7）

  val alloc    = IO(Flipped(new ROBMultiAllocIO)) // Dispatch 4-wide 分配接口
  val rollback = IO(new Bundle {                  // Memory 阶段回滚接口
    val valid  = Input(Bool())                        // 回滚使能
    val robIdx = Input(UInt(CPUConfig.robPtrWidth.W)) // 误预测指令的 ROB 指针
  })
  val refresh = IO(Vec(CPUConfig.refreshWidth, Flipped(new ROBRefreshIO)))  // Refresh 阶段完成标记接口（每 lane 一个）
  val commit  = IO(Vec(CPUConfig.commitWidth, new ROBCommitIO))             // 提交接口（每 lane 一个）
  val commitCount = IO(Output(UInt(log2Ceil(CPUConfig.commitWidth + 1).W))) // 本拍实际提交条数（0..commitWidth）
  // 输出 head 指针和 count，供 MyCPU 判定某个 refresh 是否落在当前 ROB 活跃区间内
  // 用于屏蔽"选择性 flush 泄漏"的年轻 lane 对 PRF/ReadyTable/BCT 的错误写入
  val headPtr = IO(Output(UInt((idxW + 1).W)))
  val countOut = IO(Output(UInt((idxW + 1).W)))

  // ---- Store drain 阻塞接口 ----
  val commitBlocked = IO(Input(Bool()))     // 外部通知：Store drain 未完成，阻塞 commit
  val headReady     = IO(Output(Bool()))    // 输出：ROB head 是否可提交（done && !empty）

  // 存储阵列和指针
  val rob = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new ROBEntry))))
  val head = RegInit(0.U((idxW + 1).W))   // 头指针（最老的指令）
  val tail = RegInit(0.U((idxW + 1).W))   // 尾指针（下一个空位）
  private def idx(ptr: UInt) = ptr(idxW - 1, 0)

  val count = tail - head
  val empty = count === 0.U
  val full  = count === entries.U

  alloc.availCount := entries.U - count // 输出空闲个数由 Dispatch 判断
  for (i <- 0 until 4) { // 返回连续的 ROB 指针（从当前 tail 开始）
    alloc.idxs(i) := tail + i.U
  }

  val doAlloc = alloc.request > 0.U && !rollback.valid // 判断是否真正进行分配（request > 0 且无回滚）

  // ===================== 提交输出（组合逻辑，始终驱动）=====================
  // commitMask 链：
  //   commitMask(0) = !empty && head.done && !commitBlocked
  //   commitMask(i) = commitMask(i-1) && entry(head+i).done && !empty_at_i && !extraStoreInPair
  // 其中 "extraStoreInPair" 规则：AXI 写侧每拍仅允许 1 个 store 提交（单事务在飞），
  //   因此若 lane0 本身是 Store，则 lane1..W-1 不再允许 Store 提交；
  //   若 lane0 非 Store，lane1 也可以是 Store（但 commitBlocked 通常已由 AXI 回应决定）。
  //   实现上采用"本拍已提交 Store 计数 <= 1"的保守约束：lane_i 如果是 Store 且前面 lanes 已有 Store，则截断 mask。
  val commitW = CPUConfig.commitWidth
  val headEntries = VecInit((0 until commitW).map { i => rob(idx(head + i.U)) })
  val canCommitVec = Wire(Vec(commitW, Bool()))

  for (i <- 0 until commitW) {
    val notEmpty_i  = count > i.U
    val done_i      = headEntries(i).done
    // Store 提交必须位于 lane0（简化版 AXI 单事务 + SoC_Top Store debug 接线约束）：
    //   若 i>0 且该位是 Store，则截断本拍 commitMask（Store 在下一拍变为 head 再提交）。
    val storeMustBeLane0Only = if (i == 0) false.B else headEntries(i).isStore
    val storeConflict = storeMustBeLane0Only

    val prevOk = if (i == 0) (!commitBlocked) else canCommitVec(i - 1)
    canCommitVec(i) := prevOk && notEmpty_i && done_i && !storeConflict
  }

  val canCommitHead = canCommitVec(0)
  headReady := !empty && headEntries(0).done  // 不受 commitBlocked/storeConflict 影响（用于触发 drain）

  for (i <- 0 until commitW) {
    val e = headEntries(i)
    val c = commit(i)
    c.valid         := canCommitVec(i)
    c.pc            := e.pc
    c.rd            := e.rd
    c.regWen        := e.regWen && canCommitVec(i)
    c.regWBData     := e.regWBData
    c.isStore       := e.isStore
    c.storeSeq      := e.storeSeq
    c.hasCheckpoint := e.hasCheckpoint
    c.pdst          := e.pdst
    c.stalePdst     := e.stalePdst
    c.ldst          := e.ldst
  }
  val commitNum = PopCount(canCommitVec)
  commitCount := commitNum
  headPtr  := head
  countOut := count

  // ===================== Memory 阶段回滚逻辑 =====================
  // 当 Memory 检测到分支预测错误时，tail 回滚到误预测指令的下一位，丢弃所有年轻表项，误预测指令本身保留
  when(rollback.valid) {
    tail := rollback.robIdx + 1.U
  }.otherwise {
    // ===================== 4-wide 分配逻辑 =====================
    when(doAlloc) {
      for (i <- 0 until 4) {
        when(i.U < alloc.request) { // FetchBuffer 确保有效指令优先占有低位
          val entry = rob(idx(tail + i.U)) // 分配下一表项
          entry.done          := false.B
          entry.pc            := alloc.data(i).pc
          entry.rd            := alloc.data(i).rd
          entry.regWen        := alloc.data(i).regWen
          entry.regWBData     := 0.U
          entry.isStore       := alloc.data(i).isStore
          entry.storeSeq      := alloc.data(i).storeSeq  // 写入 Store 的逻辑年龄（Commit 时传给 StoreBuffer 用于定位表项）
          entry.hasCheckpoint := alloc.data(i).hasCheckpoint  // 标记该指令是否保存了 BCT checkpoint
          // 物理寄存器映射信息存入 ROB 表项
          entry.pdst          := alloc.data(i).pdst
          entry.stalePdst     := alloc.data(i).stalePdst
          entry.ldst          := alloc.data(i).ldst
        }
      }
      tail := tail + alloc.request
    }
  }
  // ===================== Refresh 阶段完成标记 =====================
  // 多 lane Refresh：每 lane 独立更新自己的 ROB 表项。
  // 关键：选择性 flush 在上游 Dff 中使用 lane0.robIdx 判定，某对若 lane0 早于误预测分支
  // 但 lane1 晚于分支，则 lane1 可能"泄漏"到 Refresh；此时 rollback 已把 tail 回滚到
  // branch+1，泄漏的 lane1.robIdx 位于 [tail, tail+rollback区间) 外——仍可能 == tail+某偏移
  // 并误写回属于回滚后新指令的 ROB 表项。因此此处再加"ROB 活跃区间"保护：
  //   要求 (robIdx - head) < count 才允许写入（等价 idx 位于 [head, tail)）。
  for (r <- 0 until CPUConfig.refreshWidth) {
    when(refresh(r).valid) {
      val refEntry = rob(idx(refresh(r).idx))
      refEntry.done         := true.B
      refEntry.regWBData    := refresh(r).regWBData
    }
  }
  // ===================== Commit 指针更新 =====================
  when(commitNum =/= 0.U) {
    head := head + commitNum
  }
}
