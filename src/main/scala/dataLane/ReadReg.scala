package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// ReadReg（寄存器读取阶段）—— 多 lane 并行读 PRF
// ============================================================================
// 每 lane 需要读 2 个物理源寄存器，共 issueWidth*2 = prfReadPorts 个读端口。
// PRF 写优先旁路已在 PRF 内部处理；这里只负责把 psrc 分发到对应读端口、
// 把读到的数据搬到 lane payload。
// 纯组合逻辑，不含状态。
// ============================================================================
class ReadReg extends Module {
  val in  = IO(Flipped(Decoupled(new Issue_ReadReg_Payload)))
  val out = IO(Decoupled(new ReadReg_Execute_Payload))

  // ---- PRF 读取接口 ----
  val prfRead = IO(new Bundle {
    val raddr = Output(Vec(CPUConfig.prfReadPorts, UInt(CPUConfig.prfAddrWidth.W)))
    val rdata = Input(Vec(CPUConfig.prfReadPorts, UInt(32.W)))
  })

  // ---- 辅助：根据指令类型决定 use_rs1/use_rs2 ----
  private def useRs1(td: UInt): Bool = {
    val jalr = td(6); val bType = td(5); val lType = td(4)
    val iType = td(3); val sType = td(2); val rType = td(1)
    jalr || bType || iType || sType || rType || lType
  }
  private def useRs2(td: UInt): Bool = {
    val bType = td(5); val sType = td(2); val rType = td(1)
    bType || sType || rType
  }

  // 每 lane 对应 PRF 读端口 k*2 / k*2+1
  for (k <- 0 until CPUConfig.issueWidth) {
    val inL = in.bits.lanes(k)
    val td  = inL.type_decode_together
    val u1  = useRs1(td); val u2 = useRs2(td)
    prfRead.raddr(k * 2)     := Mux(u1, inL.psrc1, 0.U)
    prfRead.raddr(k * 2 + 1) := Mux(u2, inL.psrc2, 0.U)
  }

  // ---- 打包输出 payload ----
  out.bits := DontCare
  for (k <- 0 until CPUConfig.issueWidth) {
    val inL  = in.bits.lanes(k)
    val outL = out.bits.lanes(k)
    outL.pc                   := inL.pc
    outL.inst                 := inL.inst
    outL.robIdx               := inL.robIdx
    outL.src1Data             := prfRead.rdata(k * 2)
    outL.src2Data             := prfRead.rdata(k * 2 + 1)
    outL.imm                  := inL.imm
    outL.type_decode_together := inL.type_decode_together
    outL.predict_taken        := inL.predict_taken
    outL.predict_target       := inL.predict_target
    outL.bht_meta             := inL.bht_meta
    outL.regWriteEnable       := inL.regWriteEnable
    outL.sbIdx                := inL.sbIdx
    outL.isSbAlloc            := inL.isSbAlloc
    outL.storeSeqSnap         := inL.storeSeqSnap
    outL.psrc1                := inL.psrc1
    outL.psrc2                := inL.psrc2
    outL.pdst                 := inL.pdst
    outL.checkpointIdx        := inL.checkpointIdx
  }
  out.bits.validMask := in.bits.validMask

  in.ready  := out.ready
  out.valid := in.valid
}
