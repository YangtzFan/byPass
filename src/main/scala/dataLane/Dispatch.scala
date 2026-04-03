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
  val in  = IO(Flipped(Decoupled(new Rename_Dispatch_Payload)))   // 输入：来自 RenDisDff
  val out = IO(Decoupled(new DispatchPacket))                     // 输出：送入 IssueQueue
  val flush = IO(Input(Bool()))                                   // 流水线冲刷信号

  // ---- ROB 4-wide 分配接口 ----
  val robAlloc = IO(new ROBMultiAllocIO)

  // ---- StoreBuffer 分配接口 ----
  val sbAlloc = IO(new Bundle {
    val request  = Output(UInt(3.W))                                  // 请求分配的 StoreBuffer 表项数量
    val canAlloc = Input(Bool())                                      // StoreBuffer 是否有足够空间
    val idxs     = Input(Vec(4, UInt(CPUConfig.sbPtrWidth.W)))        // StoreBuffer 返回的分配指针
  })

  // ---- IssueQueue 空间查询接口 ----
  val iqFreeCount = IO(Input(UInt((log2Ceil(CPUConfig.issueQueueEntries) + 1).W)))

  // ---- 统计当前批次中 Store 指令的数量 ----
  val storeCount = Wire(UInt(3.W))
  val isStoreVec = Wire(Vec(4, Bool()))
  for (i <- 0 until 4) {
    isStoreVec(i) := in.bits.valid(i) && in.bits.entries(i).type_decode_together(2) // td(2) = sType
  }
  storeCount := PopCount(isStoreVec)

  val validCount = in.bits.count // 有效指令数量

  // ---- 派遣条件判断 ----
  // ROB 是否可以容纳所有有效指令
  val robCanAlloc = robAlloc.canAlloc
  // IssueQueue 是否有足够空间
  val iqCanAlloc = iqFreeCount >= validCount
  // StoreBuffer 是否有足够空间（仅当有 Store 指令时才需要检查）
  val sbCanAlloc = sbAlloc.canAlloc
  // 所有资源充足才能派遣
  val canDispatch = robCanAlloc && iqCanAlloc && sbCanAlloc

  // ---- 向 ROB 发送分配请求 ----
  robAlloc.request := Mux(in.valid && canDispatch && !flush, validCount, 0.U)

  // ---- 向 StoreBuffer 发送分配请求 ----
  sbAlloc.request := Mux(in.valid && canDispatch && !flush, storeCount, 0.U)

  // ---- 计算每条 Store 指令在 StoreBuffer 分配返回指针中的偏移 ----
  // sbAlloc.idxs(0..3) 是连续分配的 StoreBuffer 指针，
  // 需要将它们按 Store 指令出现的先后顺序映射
  val sbOffsets = Wire(Vec(4, UInt(3.W)))
  sbOffsets(0) := 0.U
  for (i <- 1 until 4) {
    sbOffsets(i) := sbOffsets(i - 1) +& Mux(isStoreVec(i - 1), 1.U, 0.U)
  }

  // ---- 逐路处理：生成 DispatchEntry + ROBAllocData ----
  for (i <- 0 until 4) {
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
    robAlloc.data(i).predictTaken  := entry.predict_taken
    robAlloc.data(i).predictTarget := entry.predict_target
    robAlloc.data(i).bhtMeta       := entry.bht_meta

    // ---- 向下游输出 DispatchEntry ----
    out.bits.entries(i).pc                   := entry.pc
    out.bits.entries(i).inst                 := entry.inst
    out.bits.entries(i).imm                  := entry.imm
    out.bits.entries(i).type_decode_together := td
    out.bits.entries(i).predict_taken        := entry.predict_taken
    out.bits.entries(i).predict_target       := entry.predict_target
    out.bits.entries(i).bht_meta             := entry.bht_meta
    out.bits.entries(i).robIdx               := robAlloc.idxs(i)  // ROB 返回的指针
    out.bits.entries(i).regWriteEnable       := entry.regWriteEnable
    out.bits.entries(i).sbIdx                := sbAlloc.idxs(sbOffsets(i)(1, 0)) // StoreBuffer 返回的指针
    out.bits.entries(i).isSbAlloc            := sType && in.bits.valid(i) // 仅 Store 指令分配了 StoreBuffer
  }

  // validMask 和 count 透传
  out.bits.valid := in.bits.valid
  out.bits.count := validCount

  // ---- 握手信号 ----
  // 任何资源不足时反压上游；flush 时抑制输出
  in.ready  := out.ready && canDispatch
  out.valid := in.valid && canDispatch && !flush
}
