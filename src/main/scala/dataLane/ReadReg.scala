package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// ReadReg（寄存器读取阶段）—— 宽度 1
// ============================================================================
// 从 PRF（物理寄存器堆）读取操作数，按物理寄存器编号 psrc1/psrc2 读取。
// 读取的值将作为旁路转发链的兜底值，经 RRExDff 传给 Execute 阶段。
//
// 设计要点：
//   - 从 IssRRDff 接收指令信息（含 psrc1/psrc2 物理寄存器编号）
//   - 根据指令类型确定是否需要读取 psrc1/psrc2
//   - 从 PRF 读取物理寄存器值
//   - 打包输出 ReadReg_Execute_Payload 给 RRExDff → Execute
//   - 纯组合逻辑阶段，不内含状态（状态在 PRF 中）
// ============================================================================
class ReadReg extends Module {
  val in  = IO(Flipped(Decoupled(new Issue_ReadReg_Payload)))     // 输入：来自 IssRRDff
  val out = IO(Decoupled(new ReadReg_Execute_Payload))            // 输出：送往 RRExDff → Execute

  // ---- PRF 读取接口（按物理寄存器编号读取）----
  val prfRead = IO(new Bundle {
    val raddr1 = Output(UInt(CPUConfig.prfAddrWidth.W))   // 读 psrc1 地址
    val raddr2 = Output(UInt(CPUConfig.prfAddrWidth.W))   // 读 psrc2 地址
    val rdata1 = Input(UInt(32.W))                         // psrc1 数据
    val rdata2 = Input(UInt(32.W))                         // psrc2 数据
  })

  // ---- 指令类型提取 ----
  val td    = in.bits.type_decode_together
  val jalr  = td(6)
  val bType = td(5)
  val lType = td(4)
  val iType = td(3)
  val sType = td(2)
  val rType = td(1)

  // ---- 确定是否需要读取 psrc1/psrc2 ----
  val use_rs1 = jalr || bType || iType || sType || rType || lType
  val use_rs2 = bType || sType || rType

  // ---- PRF 读取（使用物理寄存器编号）----
  prfRead.raddr1 := Mux(use_rs1, in.bits.psrc1, 0.U)
  prfRead.raddr2 := Mux(use_rs2, in.bits.psrc2, 0.U)

  // ---- 输出结果打包 ----
  out.bits.pc                   := in.bits.pc
  out.bits.inst                 := in.bits.inst
  out.bits.robIdx               := in.bits.robIdx
  out.bits.src1Data             := prfRead.rdata1 // PRF 读取的 psrc1 值（旁路链兜底值）
  out.bits.src2Data             := prfRead.rdata2 // PRF 读取的 psrc2 值（旁路链兜底值）
  out.bits.imm                  := in.bits.imm
  out.bits.type_decode_together := td
  out.bits.predict_taken        := in.bits.predict_taken
  out.bits.predict_target       := in.bits.predict_target
  out.bits.bht_meta             := in.bits.bht_meta
  out.bits.regWriteEnable       := in.bits.regWriteEnable
  out.bits.sbIdx                := in.bits.sbIdx
  out.bits.isSbAlloc            := in.bits.isSbAlloc
  out.bits.storeSeqSnap         := in.bits.storeSeqSnap  // 传递 storeSeq 快照到 Execute 阶段
  // 物理寄存器映射信息透传
  out.bits.psrc1                := in.bits.psrc1
  out.bits.psrc2                := in.bits.psrc2
  out.bits.pdst                 := in.bits.pdst
  out.bits.stalePdst            := in.bits.stalePdst
  out.bits.ldst                 := in.bits.ldst
  out.bits.checkpointIdx        := in.bits.checkpointIdx // 分支 checkpoint 索引透传

  // ---- 握手信号 ----
  // ReadReg 是纯组合逻辑阶段，直接透传握手
  in.ready  := out.ready
  out.valid := in.valid
}
