package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// Dispatch（派发阶段）—— 4-wide，负责 ROB 分配 + StoreBuffer 分配
// ============================================================================
// 从 RenDisDff 接收最多 4 条指令，为每条指令分配 ROB 表项，
// 为 Store 指令分配 StoreBuffer 表项，然后输出到 IssueQueue。
//
// 派遣数量计算：
//   dispatch_n = min(valid_rename_n, rob_free_n, iq_free_n)
//   其中 valid_rename_n 是上游有效指令数，
//   rob_free_n 是 ROB 空闲表项数（由 ROB 的 canAlloc 间接保证），
//   iq_free_n 是 IssueQueue 空闲槽位数。
//
// 停顿条件：
//   - ROB 无法分配足够表项
//   - IssueQueue 空间不足
//   - StoreBuffer 空间不足（当有 Store 指令时）
// ============================================================================
class Dispatch extends Module {
  val in  = IO(Flipped(Decoupled(new Rename_Dispatch_Payload))) // 输入：来自 RenDisDff
  val out = IO(new Bundle {                                     // 输出：送入 IssueQueue 的写入数据
    val entries    = Output(Vec(4, new DispatchEntry))
    val validCount = Output(UInt(3.W))
    val valid      = Output(Bool())                             // 本周期是否有有效的 Dispatch 输出
  })
  val flush = IO(Input(Bool()))                                 // 流水线冲刷信号
  // ---- ROB 4-wide 分配接口 ----
  val robAlloc = IO(new ROBMultiAllocIO)
  // ---- StoreBuffer 分配接口 ----
  val sbAlloc = IO(new SBAllocIO)
  // ---- IssueQueue 分配接口（参照 StoreBuffer 的分配模式）----
  val iqAlloc = IO(new IQAllocIO)

  val validCount = in.bits.validCount // 取出 Rename 统计的有效指令数量
  val storeCount = in.bits.storeCount // 取出 Rename 统计的既是 store 又有效的指令数量

  // ---- 派遣条件判断 ----
  val robCanAlloc = robAlloc.canAlloc // ROB 是否可以容纳所有有效指令
  val sbCanAlloc  = sbAlloc.canAlloc // StoreBuffer 是否有足够空间（仅当有 Store 指令时才需要检查）
  val iqCanAlloc  = iqAlloc.canAlloc // IssueQueue 是否有足够空间
  val canDispatch = robCanAlloc && sbCanAlloc && iqCanAlloc // 所有资源充足才能派遣
  val doDispatch  = in.valid && canDispatch && !flush      // 流水线冲刷时不派遣

  // ---- 向 ROB 发送分配请求，返回请求分配表项数目 ----
  robAlloc.request := Mux(doDispatch, validCount, 0.U)

  // ---- 向 StoreBuffer 发送分配请求，返回请求分配表项数目 ----
  sbAlloc.request := Mux(doDispatch, storeCount, 0.U)

  // ---- 向 IssueQueue 发送分配请求（参照 StoreBuffer 的分配模式）----
  iqAlloc.request := Mux(doDispatch, validCount, 0.U)

  // ---- 计算每条 Store 指令在 StoreBuffer 分配返回指针中的偏移 ----
  // sbAlloc.idxs(0..3) 是连续分配的 StoreBuffer 指针，需要将它们按 Store 指令出现的先后顺序映射
  val sbOffsets = WireInit(VecInit(Seq.fill(4)(0.U(2.W))))
  for (i <- 1 until 4) {
    sbOffsets(i) := sbOffsets(i - 1) +& Mux(in.bits.entries(i - 1).memWriteEnable, 1.U, 0.U)
  }

  // ---- 逐路处理：生成 DispatchEntry + ROBAllocData ----
  for (i <- 0 until 4) { // FetchBuffer 确保指令优先填满低位
    val entry = in.bits.entries(i)
    val td    = entry.type_decode_together

    val uType = td(8)
    val jal   = td(7)
    val jalr  = td(6)
    val bType = td(5)
    val lType = td(4)
    val iType = td(3)
    val sType = td(2)
    val rType = td(1)

    val rd = entry.inst(11, 7) // 目标寄存器编号

    // ---- ROB 分配数据 ----
    robAlloc.data(i).pc            := entry.pc
    robAlloc.data(i).inst          := entry.inst
    robAlloc.data(i).regWen        := entry.regWriteEnable
    robAlloc.data(i).rd            := rd
    robAlloc.data(i).isLoad        := lType
    robAlloc.data(i).isStore       := sType
    robAlloc.data(i).isBranch      := bType
    robAlloc.data(i).isJump        := jal || jalr
    robAlloc.data(i).hasCheckpoint := entry.hasCheckpoint  // 从 Rename 传递：仅该组第一个被预测分支为 true
    robAlloc.data(i).predictTaken  := entry.predict_taken
    robAlloc.data(i).predictTarget := entry.predict_target
    robAlloc.data(i).bhtMeta       := entry.bht_meta
    // Store 指令的 storeSeq：从 SBAllocIO 返回值中按 sbOffsets 映射获取
    // 非 Store 指令的 storeSeq 字段为 0（ROB Commit 时不会使用）
    robAlloc.data(i).storeSeq      := Mux(sType, sbAlloc.storeSeqs(sbOffsets(i)), 0.U)
    // 物理寄存器映射信息透传到 ROB
    robAlloc.data(i).pdst          := entry.pdst
    robAlloc.data(i).stalePdst     := entry.stalePdst
    robAlloc.data(i).ldst          := entry.ldst

    // ---- 向下游输出 DispatchEntry ----
    out.entries(i).pc                   := entry.pc
    out.entries(i).inst                 := entry.inst
    out.entries(i).imm                  := entry.imm
    out.entries(i).type_decode_together := td
    out.entries(i).predict_taken        := entry.predict_taken
    out.entries(i).predict_target       := entry.predict_target
    out.entries(i).bht_meta             := entry.bht_meta
    out.entries(i).robIdx               := robAlloc.idxs(i) // ROB 返回的指针
    out.entries(i).regWriteEnable       := entry.regWriteEnable
    out.entries(i).memWriteEnable       := entry.memWriteEnable
    out.entries(i).sbIdx                := sbAlloc.idxs(sbOffsets(i)) // StoreBuffer 返回的物理槽位索引
    out.entries(i).isSbAlloc            := sType && entry.valid // 仅 Store 指令分配了 StoreBuffer
    // storeSeqSnap：每条指令记录"该指令之前（不含自身）一共有多少 Store 已分配"的 nextStoreSeq 快照
    // 对于 Load：在 Memory 阶段用于 S2L 查询边界（只查 storeSeq < snap 的 Store）
    // 对于 mispredict 指令：在回滚时用于精确清除 StoreBuffer 中 storeSeq >= snap 的表项
    //
    // 关键：snap 必须只包含程序顺序上"更老"的 Store，不能包含同批次中位于自身之后的 Store
    //   sbOffsets(i) 正好等于位置 [0, i-1] 中 Store 的数量，所以 storeSeqSnap = nextStoreSeq + sbOffsets(i)
    //   例如批次 [Store(seq=N), Load, Store(seq=N+1), Load]：
    //     - inst[0](Store): sbOffsets(0)=0, snap=N   → 只看 seq < N 的更老 Store
    //     - inst[1](Load):  sbOffsets(1)=1, snap=N+1 → 看 seq < N+1，即包括 inst[0] 的 Store ✔
    //     - inst[2](Store): sbOffsets(2)=1, snap=N+1 → 只看 seq < N+1 的更老 Store
    //     - inst[3](Load):  sbOffsets(3)=2, snap=N+2 → 包括 inst[0] 和 inst[2] 的 Store ✔
    out.entries(i).storeSeqSnap         := sbAlloc.nextStoreSeq + sbOffsets(i)
    out.entries(i).valid                := entry.valid
    // 物理寄存器映射信息透传到 IssueQueue
    out.entries(i).psrc1                := entry.psrc1
    out.entries(i).psrc2                := entry.psrc2
    out.entries(i).pdst                 := entry.pdst
    out.entries(i).stalePdst            := entry.stalePdst
    out.entries(i).ldst                 := entry.ldst
    out.entries(i).checkpointIdx        := entry.checkpointIdx // 分支 checkpoint 索引透传
  }
  out.validCount := validCount // 来自 Rename 阶段的有效指令信息

  // ---- 握手信号 ----
  // IssueQueue、ROB、StoreBuffer 任一资源不足时反压上游；flush 时抑制输出
  in.ready  := canDispatch
  out.valid := doDispatch
}
