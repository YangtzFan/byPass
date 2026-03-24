import chisel3._

class ID_EX_dff extends Module {
  val io = IO(new Bundle {
    val inst_addr_i = Input(UInt(32.W))
    val inst_i = Input(UInt(32.W))
    val reg_rdata1_i = Input(UInt(32.W))
    val reg_rdata2_i = Input(UInt(32.W))
    val imm_i = Input(UInt(32.W))
    val type_decode_together_i = Input(UInt(9.W))
    val ex_hold_dff = Input(UInt(3.W))
    val pipeline_flush_enable_i = Input(Bool())

    val inst_addr_o = Output(UInt(32.W))
    val inst_o = Output(UInt(32.W))
    val reg_rdata1_o = Output(UInt(32.W))
    val reg_rdata2_o = Output(UInt(32.W))
    val imm_o = Output(UInt(32.W))
    val type_decode_together_o = Output(UInt(9.W))
  })

  val lden = Mux(io.pipeline_flush_enable_i, true.B, !(io.ex_hold_dff > 3.U))
  val clearNow = (io.ex_hold_dff === 3.U) || io.pipeline_flush_enable_i

  val instAddrReg = RegInit(0.U(32.W))
  val instReg = RegInit(0.U(32.W))
  val r1Reg = RegInit(0.U(32.W))
  val r2Reg = RegInit(0.U(32.W))
  val immReg = RegInit(0.U(32.W))
  val tReg = RegInit(0.U(9.W))

  when(clearNow) {
    instAddrReg := 0.U
    instReg := 0.U
    r1Reg := 0.U
    r2Reg := 0.U
    immReg := 0.U
    tReg := 0.U
  }.elsewhen(lden) {
    instAddrReg := io.inst_addr_i
    instReg := io.inst_i
    r1Reg := io.reg_rdata1_i
    r2Reg := io.reg_rdata2_i
    immReg := io.imm_i
    tReg := io.type_decode_together_i
  }

  io.inst_addr_o := instAddrReg
  io.inst_o := instReg
  io.reg_rdata1_o := r1Reg
  io.reg_rdata2_o := r2Reg
  io.imm_o := immReg
  io.type_decode_together_o := tReg
}
