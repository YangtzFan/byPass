import chisel3._

class MEM_WB_dff extends Module {
  val io = IO(new Bundle {

    val inst_addr_i = Input(UInt(32.W))
    val inst_rd_i = Input(UInt(5.W))
    val data_i = Input(UInt(32.W))
    val type_decode_together_i = Input(UInt(9.W))

    val wb_hold_dff = Input(UInt(3.W))

    val inst_addr_o = Output(UInt(32.W))
    val inst_rd_o = Output(UInt(5.W))
    val data_o = Output(UInt(32.W))
    val type_decode_together_o = Output(UInt(9.W))
  })

  val clearNow = io.wb_hold_dff === 5.U

  val instAddrReg = RegInit(0.U(32.W))
  val rdReg = RegInit(0.U(5.W))
  val dataReg = RegInit(0.U(32.W))
  val tReg = RegInit(0.U(9.W))

  when(clearNow) {
    instAddrReg := 0.U
    rdReg := 0.U
    dataReg := 0.U
    tReg := 0.U
  }.otherwise {
    instAddrReg := io.inst_addr_i
    rdReg := io.inst_rd_i
    dataReg := io.data_i
    tReg := io.type_decode_together_i
  }

  io.inst_addr_o := instAddrReg
  io.inst_rd_o := rdReg
  io.data_o := dataReg
  io.type_decode_together_o := tReg
}
