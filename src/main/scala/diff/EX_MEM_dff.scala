import chisel3._

class EX_MEX_dff extends Module {
  val io = IO(new Bundle {

    val inst_addr_i = Input(UInt(32.W))
    val inst_funct3_i = Input(UInt(3.W))
    val inst_rd_i = Input(UInt(5.W))
    val data_i = Input(UInt(32.W))
    val reg_rdata2_i = Input(UInt(32.W))
    val type_decode_together_i = Input(UInt(9.W))

    val mem_hold_dff = Input(UInt(3.W))

    val inst_addr_o = Output(UInt(32.W))
    val inst_funct3_o = Output(UInt(3.W))
    val inst_rd_o = Output(UInt(5.W))
    val data_o = Output(UInt(32.W))
    val reg_rdata2_o = Output(UInt(32.W))
    val type_decode_together_o = Output(UInt(9.W))
  })

  val lden = !(io.mem_hold_dff > 4.U)
  val clearNow = io.mem_hold_dff === 4.U

  val instAddrReg = RegInit(0.U(32.W))
  val funct3Reg = RegInit(0.U(3.W))
  val rdReg = RegInit(0.U(5.W))
  val dataReg = RegInit(0.U(32.W))
  val r2Reg = RegInit(0.U(32.W))
  val tReg = RegInit(0.U(9.W))

  when(clearNow) {
    instAddrReg := 0.U
    funct3Reg := 0.U
    rdReg := 0.U
    dataReg := 0.U
    r2Reg := 0.U
    tReg := 0.U
  }.elsewhen(lden) {
    instAddrReg := io.inst_addr_i
    funct3Reg := io.inst_funct3_i
    rdReg := io.inst_rd_i
    dataReg := io.data_i
    r2Reg := io.reg_rdata2_i
    tReg := io.type_decode_together_i
  }

  io.inst_addr_o := instAddrReg
  io.inst_funct3_o := funct3Reg
  io.inst_rd_o := rdReg
  io.data_o := dataReg
  io.reg_rdata2_o := r2Reg
  io.type_decode_together_o := tReg
}
