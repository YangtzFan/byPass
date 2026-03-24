import chisel3._
import chisel3.util._

class ALU extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(32.W))
    val b = Input(UInt(32.W))
    val ctrl = Input(UInt(5.W))
    val enable = Input(Bool())
    val result = Output(UInt(32.W))
    val zero = Output(Bool())
  })

  val resultWire = WireDefault(0.U(32.W))
  when(io.enable) {
    switch(io.ctrl) {
      is("b00000".U) { resultWire := io.a + io.b }
      is("b01000".U) { resultWire := io.a - io.b }
      is("b00001".U) { resultWire := io.a << io.b(4, 0) }
      is("b00010".U) { resultWire := (io.a.asSInt < io.b.asSInt).asUInt }
      is("b00011".U) { resultWire := (io.a < io.b).asUInt }
      is("b00100".U) { resultWire := io.a ^ io.b }
      is("b00101".U) { resultWire := io.a >> io.b(4, 0) }
      is("b01101".U) { resultWire := (io.a.asSInt >> io.b(4, 0)).asUInt }
      is("b00110".U) { resultWire := io.a | io.b }
      is("b00111".U) { resultWire := io.a & io.b }
    }
  }

  io.result := resultWire
  io.zero := resultWire.orR
}
