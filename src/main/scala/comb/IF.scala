import chisel3._
import chisel3.util._

class IF extends Module {
  val io = IO(new Bundle {
    val inst_i = Input(UInt(32.W))
    val type_decode_together_o = Output(UInt(3.W))
  })

  val opcode = io.inst_i(6, 0)
  val jal = opcode === "b1101111".U
  val jalr = opcode === "b1100111".U
  val bType = opcode === "b1100011".U

  io.type_decode_together_o := Cat(jal, jalr, bType)
}
