import chisel3._
import chisel3.util._

class IF extends Module {
  val in = IO(Flipped(Decoupled(UInt(32.W))))
  val out = IO(Decoupled(new IF_ID_Payload))
  val io = IO(new Bundle {
    val inst_i = Input(UInt(32.W))
    val inst_addr_o = Output(UInt(32.W))
  })
  
  io.inst_addr_o := in.bits

  val opcode = io.inst_i(6, 0)
  val jal = opcode === "b1101111".U
  val jalr = opcode === "b1100111".U
  val bType = opcode === "b1100011".U

  out.bits.inst_addr := in.bits
  out.bits.inst := io.inst_i
  out.bits.type_decode_together := Cat(jal, jalr, bType)

  in.ready := out.ready
  out.valid := in.valid
}
