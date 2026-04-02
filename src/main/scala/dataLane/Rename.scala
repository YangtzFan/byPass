package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// Rename（重命名阶段）—— 4-wide 占位级
// ============================================================================
// 当前实现为占位级，不进行真正的寄存器重命名（无 PRF/RAT），仅完成：
//   1. 为每条有效指令分配 ROB 表项（通过 ROBMultiAllocIO 接口，最多 4 条/周期）
//   2. 计算 regWriteEnable（从 type_decode_together 提取）
//   3. 将 DecodePacket 转换为 RenamePacket 输出到 RenameBuffer
//
// 停顿条件：ROB 无法满足分配请求时，反压上游 DecRenDff
// ============================================================================
class Rename extends Module {

  val in  = IO(Flipped(Decoupled(new Decode_Rename_Payload)))    // 来自 DecRenDff 的 4 条译码结果
  val out = IO(Decoupled(new RenamePacket))              // 输出到 RenameBuffer
  val flush = IO(Input(Bool()))                          // 流水线冲刷信号
  val robAlloc = IO(new ROBMultiAllocIO)                 // ROB 4-wide 分配接口

  val validCount = PopCount(in.bits.valid) // 有效指令数量
  robAlloc.request := Mux(in.valid && out.ready && !flush, validCount, 0.U) // 输入有效、下游就绪、未冲刷时，才向 ROB 发送分配请求

  // ---- 逐路处理：生成 RenameEntry + ROBAllocData ----
  for (i <- 0 until 4) {
    val decoded = in.bits.insts(i)
    val td      = decoded.type_decode_together

    // 从 type_decode_together 提取指令类型
    val uType = td(8)
    val jal   = td(7)
    val jalr  = td(6)
    val bType = td(5)
    val lType = td(4)
    val iType = td(3)
    val sType = td(2)
    val rType = td(1)

    val regWriteEnable = uType || jal || jalr || lType || iType || rType // 写回寄存器的所有指令
    val rd = decoded.inst(11, 7) // 目标寄存器编号

    // ---- ROB 分配数据 ----
    robAlloc.data(i).pc            := decoded.pc
    robAlloc.data(i).inst          := decoded.inst
    robAlloc.data(i).regWen        := regWriteEnable
    robAlloc.data(i).rd            := rd
    robAlloc.data(i).isLoad        := lType
    robAlloc.data(i).isStore       := sType
    robAlloc.data(i).isBranch      := bType
    robAlloc.data(i).isJump        := jal || jalr
    robAlloc.data(i).predictTaken  := decoded.predict_taken
    robAlloc.data(i).predictTarget := decoded.predict_target
    robAlloc.data(i).bhtMeta       := decoded.bht_meta

    // ---- 向下游继续输出 ----
    out.bits.entries(i).pc                   := decoded.pc
    out.bits.entries(i).inst                 := decoded.inst
    out.bits.entries(i).imm                  := decoded.imm
    out.bits.entries(i).type_decode_together := td
    out.bits.entries(i).predict_taken        := decoded.predict_taken
    out.bits.entries(i).predict_target       := decoded.predict_target
    out.bits.entries(i).bht_meta             := decoded.bht_meta
    out.bits.entries(i).robIdx               := robAlloc.idxs(i)  // ROB 返回的指针
    out.bits.entries(i).regWriteEnable       := regWriteEnable
  }

  // validMask 和 count 直接透传
  out.bits.valid := in.bits.valid
  out.bits.count := validCount

  // ---- 握手信号 ----
  // 当 ROB 无法分配足够表项时，反压上游，输出无效
  in.ready  := out.ready && robAlloc.canAlloc
  out.valid := in.valid && robAlloc.canAlloc && !flush
}
