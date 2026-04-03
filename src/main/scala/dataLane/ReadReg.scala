package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// ReadReg（寄存器读取阶段）—— 宽度 1
// ============================================================================
// 专门用于读取寄存器堆，获取 rs1/rs2 的值。
// 这些值将作为旁路转发链的兜底值，经 RRExDff 传给 Execute 阶段。
//
// 设计要点：
//   - 从 IssRRDff 接收指令信息（含 robIdx、imm 等）
//   - 根据指令类型确定是否需要读取 rs1/rs2
//   - 读取寄存器堆获取 rs1/rs2 的值
//   - 打包输出 ReadReg_Execute_Payload 给 RRExDff → Execute
//   - 纯组合逻辑阶段，不内含状态（状态在 RegFile 中）
// ============================================================================
class ReadReg extends Module {
  val in  = IO(Flipped(Decoupled(new Issue_ReadReg_Payload)))     // 输入：来自 IssRRDff
  val out = IO(Decoupled(new ReadReg_Execute_Payload))            // 输出：送往 RRExDff → Execute

  // ---- 寄存器堆读取接口 ----
  val regRead = IO(new Bundle {
    val raddr1 = Output(UInt(5.W))   // 读 rs1 地址
    val raddr2 = Output(UInt(5.W))   // 读 rs2 地址
    val rdata1 = Input(UInt(32.W))   // rs1 数据
    val rdata2 = Input(UInt(32.W))   // rs2 数据
  })

  // ---- 指令字段提取 ----
  val inst = in.bits.inst
  val rs1  = inst(19, 15)
  val rs2  = inst(24, 20)

  // ---- 指令类型提取 ----
  val td    = in.bits.type_decode_together
  val jalr  = td(6)
  val bType = td(5)
  val lType = td(4)
  val iType = td(3)
  val sType = td(2)
  val rType = td(1)

  // ---- 确定是否需要读取 rs1/rs2 ----
  val use_rs1 = jalr || bType || iType || sType || rType || lType
  val use_rs2 = bType || sType || rType

  // ---- 寄存器堆读取 ----
  regRead.raddr1 := Mux(use_rs1, rs1, 0.U)
  regRead.raddr2 := Mux(use_rs2, rs2, 0.U)

  // ---- 输出结果打包 ----
  out.bits.pc                   := in.bits.pc
  out.bits.inst                 := in.bits.inst
  out.bits.robIdx               := in.bits.robIdx
  out.bits.src1Data             := regRead.rdata1   // 寄存器堆读取的 rs1 值（旁路链兜底值）
  out.bits.src2Data             := regRead.rdata2   // 寄存器堆读取的 rs2 值（旁路链兜底值）
  out.bits.imm                  := in.bits.imm
  out.bits.type_decode_together := td
  out.bits.predict_taken        := in.bits.predict_taken
  out.bits.predict_target       := in.bits.predict_target
  out.bits.bht_meta             := in.bits.bht_meta
  out.bits.regWriteEnable       := in.bits.regWriteEnable
  out.bits.sbIdx                := in.bits.sbIdx
  out.bits.isSbAlloc            := in.bits.isSbAlloc

  // ---- 握手信号 ----
  // ReadReg 是纯组合逻辑阶段，直接透传握手
  in.ready  := out.ready
  out.valid := in.valid
}
