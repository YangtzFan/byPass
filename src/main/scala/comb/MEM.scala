import chisel3._

class MEM extends Module {
  val io = IO(new Bundle {
    val inst_funct3_i = Input(UInt(3.W))
    val inst_rd_i = Input(UInt(5.W))
    val data_i = Input(UInt(32.W))
    val reg_rdata2_i = Input(UInt(32.W))
    val type_decode_together_i = Input(UInt(9.W))

    val ram_addr_o = Output(UInt(32.W))
    val ram_wdata_o = Output(UInt(32.W))
    val ram_wen_o = Output(Bool())
    val ram_mask_o = Output(UInt(3.W))
    val ram_rdata_i = Input(UInt(32.W))

    val inst_rd_o = Output(UInt(5.W))
    val data_o = Output(UInt(32.W))
    val type_decode_together_o = Output(UInt(9.W))
  })

  val uType = io.type_decode_together_i(8)
  val jal = io.type_decode_together_i(7)
  val jalr = io.type_decode_together_i(6)
  val lType = io.type_decode_together_i(4)
  val iType = io.type_decode_together_i(3)
  val sType = io.type_decode_together_i(2)
  val rType = io.type_decode_together_i(1)

  io.ram_addr_o := Mux(lType || sType, io.data_i, 0.U)
  io.ram_wen_o := sType
  io.ram_mask_o := io.inst_funct3_i
  io.ram_wdata_o := io.reg_rdata2_i

  io.inst_rd_o := Mux(uType || jal || jalr || lType || iType || rType, io.inst_rd_i, 0.U)
  io.data_o := Mux(uType || jal || jalr || iType || rType, io.data_i, Mux(lType, io.ram_rdata_i, 0.U))
  io.type_decode_together_o := io.type_decode_together_i
}
