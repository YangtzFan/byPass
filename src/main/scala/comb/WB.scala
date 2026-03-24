import chisel3._

class WB extends Module {
  val io = IO(new Bundle {
    val type_decode_together_i = Input(UInt(9.W))
    val inst_bubble_ifornot = Output(Bool())
    val reg_wen = Output(Bool())
  })

  val uType = io.type_decode_together_i(8)
  val jal = io.type_decode_together_i(7)
  val jalr = io.type_decode_together_i(6)
  val lType = io.type_decode_together_i(4)
  val iType = io.type_decode_together_i(3)
  val rType = io.type_decode_together_i(1)
  val other = io.type_decode_together_i(0)

  io.inst_bubble_ifornot := other || io.type_decode_together_i(8, 1).orR
  io.reg_wen := uType || jal || jalr || lType || iType || rType
}
