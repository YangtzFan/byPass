package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// Dispatch（派发阶段）—— 4-wide，负责 ROB 分配 + StoreBuffer 分配
// ============================================================================
// 无寄存器重命名版本：不再透传物理寄存器映射信息。
// 从 RenDisDff 接收最多 4 条指令，为每条指令分配 ROB 表项，
// 为 Store 指令分配 StoreBuffer 表项，然后输出到 IssueQueue。
// ============================================================================
class Dispatch extends Module {
  val in  = IO(Flipped(Decoupled(new Rename_Dispatch_Payload))) // 输入：来自 RenDisDff
  val flush = IO(Input(Bool()))                                 // 流水线冲刷信号
  // ---- ROB 4-wide 分配接口 ----
  val robAlloc = IO(new ROBMultiAllocIO)
  // ---- StoreBuffer 分配接口 ----
  val sbAlloc = IO(new SBAllocIO)
  // ---- IssueQueue 分配接口（参照 StoreBuffer 的分配模式）----
  val iqAlloc = IO(new IQAllocIO)
  val out = IO(Output(new IQWriteData))

  val validCount = in.bits.validCount // 取出 Rename 统计的有效指令数量
  val storeCount = in.bits.storeCount // 取出 Rename 统计的既是 store 又有效的指令数量

  // ---- 派遣条件判断 ----
  val robCanAlloc = robAlloc.canAlloc // ROB 是否可以容纳所有有效指令
  val sbCanAlloc  = sbAlloc.canAlloc // StoreBuffer 是否有足够空间
  val iqCanAlloc  = iqAlloc.canAlloc // IssueQueue 是否有足够空间
  val canDispatch = robCanAlloc && sbCanAlloc && iqCanAlloc // 所有资源充足才能派遣
  val doDispatch  = in.valid && canDispatch && !flush      // 流水线冲刷时不派遣

  // ---- 向 ROB、StoreBuffer、IssueQueue 发送分配请求，返回请求分配表项数目 ----
  robAlloc.request := Mux(doDispatch, validCount, 0.U)
  sbAlloc.request := Mux(doDispatch, storeCount, 0.U)
  iqAlloc.request := Mux(doDispatch, validCount, 0.U)

  // ---- 计算每条 Store 指令在 StoreBuffer 分配返回指针中的偏移 ----
  val sbOffsets = WireInit(VecInit(Seq.fill(4)(0.U(2.W))))
  for (i <- 1 until 4) {
    sbOffsets(i) := sbOffsets(i - 1) +& Mux(in.bits.entries(i - 1).type_decode_together(2), 1.U, 0.U)
  }

  // ---- 逐路处理：生成 DispatchEntry + ROBAllocData ----
  for (i <- 0 until 4) {
    val entry = in.bits.entries(i) // DecodedInst（无物理寄存器映射）
    val td    = entry.type_decode_together

    val uType = td(8)
    val jal   = td(7)
    val jalr  = td(6)
    val bType = td(5)
    val lType = td(4)
    val iType = td(3)
    val sType = td(2)
    val rType = td(1)

    val rd = entry.inst(11, 7) // 逻辑目标寄存器编号

    // ---- ROB 分配数据（无寄存器重命名版本：不含物理寄存器映射）----
    robAlloc.data(i).pc            := entry.pc
    robAlloc.data(i).inst          := entry.inst
    robAlloc.data(i).rd            := rd
    robAlloc.data(i).regWen        := entry.regWriteEnable
    robAlloc.data(i).isLoad        := lType
    robAlloc.data(i).isStore       := sType
    robAlloc.data(i).isBranch      := bType
    robAlloc.data(i).isJump        := jal || jalr
    robAlloc.data(i).predictTaken  := entry.predict_taken
    robAlloc.data(i).predictTarget := entry.predict_target
    robAlloc.data(i).bhtMeta       := entry.bht_meta
    // Store 指令的 storeSeq：从 SBAllocIO 返回值中按 sbOffsets 映射获取
    robAlloc.data(i).storeSeq      := Mux(sType, sbAlloc.storeSeqs(sbOffsets(i)), 0.U)

    // ---- 向下游输出 DispatchEntry（无物理寄存器映射信息）----
    out.entries(i).pc                   := entry.pc
    out.entries(i).inst                 := entry.inst
    out.entries(i).imm                  := entry.imm
    out.entries(i).type_decode_together := td
    out.entries(i).predict_taken        := entry.predict_taken
    out.entries(i).predict_target       := entry.predict_target
    out.entries(i).bht_meta             := entry.bht_meta
    out.entries(i).robIdx               := robAlloc.idxs(i) // ROB 返回的指针
    out.entries(i).regWriteEnable       := entry.regWriteEnable
    out.entries(i).sbIdx                := sbAlloc.idxs(sbOffsets(i)) // StoreBuffer 返回的物理槽位索引
    out.entries(i).isSbAlloc            := sType && entry.valid // 仅 Store 指令分配了 StoreBuffer
    // storeSeqSnap：每条指令记录"该指令之前（不含自身）一共有多少 Store 已分配"的 nextStoreSeq 快照
    out.entries(i).storeSeqSnap         := sbAlloc.nextStoreSeq + sbOffsets(i)
    out.entries(i).valid                := entry.valid
  }
  out.validCount := validCount

  // ---- 握手信号 ----
  in.ready  := canDispatch
  out.valid := doDispatch
}
