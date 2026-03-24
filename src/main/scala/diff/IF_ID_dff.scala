import chisel3._

class IF_ID_dff extends Module {
  val io = IO(new Bundle {
    val inst_addr_i = Input(UInt(32.W))
    val inst_i = Input(UInt(32.W))
    val reg_rdata1_i = Input(UInt(32.W))
    val reg_rdata1_forward_bit_i = Input(Bool())
    val id_hold_dff = Input(UInt(3.W))
    val pipeline_flush_enable_i = Input(Bool())
    val inst_addr_o = Output(UInt(32.W))
    val inst_o = Output(UInt(32.W))
    val reg_rdata1_o = Output(UInt(32.W))
    val reg_rdata1_forward_bit_o = Output(Bool())
  })

  val lden = Mux(io.pipeline_flush_enable_i, true.B, !(io.id_hold_dff > 2.U))
  val clearNow = (io.id_hold_dff === 1.U) || (io.id_hold_dff === 2.U) || io.pipeline_flush_enable_i

  val instAddrReg = RegInit(0.U(32.W))
  val instReg = RegInit(0.U(32.W))
  val regDataReg = RegInit(0.U(32.W))
  val regFwdReg = RegInit(false.B)

  when(clearNow) {
    instAddrReg := 0.U
    instReg := 0.U
    regDataReg := 0.U
    regFwdReg := false.B
  }.elsewhen(lden) {
    instAddrReg := io.inst_addr_i
    instReg := io.inst_i
    regDataReg := io.reg_rdata1_i
    regFwdReg := io.reg_rdata1_forward_bit_i
  }

  io.inst_addr_o := instAddrReg
  io.inst_o := instReg
  io.reg_rdata1_o := regDataReg
  io.reg_rdata1_forward_bit_o := regFwdReg
}
