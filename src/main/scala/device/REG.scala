import chisel3._

class REG extends Module {
  val io = IO(new Bundle {
    val reg_wen_i = Input(Bool())
    val reg_waddr_i = Input(UInt(5.W))
    val reg_wdata_i = Input(UInt(32.W))
    val reg_raddr1_i = Input(UInt(5.W))
    val reg_raddr2_i = Input(UInt(5.W))
    val reg_rdata1_o = Output(UInt(32.W))
    val reg_rdata2_o = Output(UInt(32.W))
  })

  val regFile = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))

  when(io.reg_wen_i && io.reg_waddr_i =/= 0.U) {
    regFile(io.reg_waddr_i) := io.reg_wdata_i
  }

  io.reg_rdata1_o := Mux(io.reg_raddr1_i === 0.U, 0.U, regFile(io.reg_raddr1_i))
  io.reg_rdata2_o := Mux(io.reg_raddr2_i === 0.U, 0.U, regFile(io.reg_raddr2_i))
}
