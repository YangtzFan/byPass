package mycpu.device

import chisel3._
import chisel3.util._

class ALU extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(32.W))
    val b = Input(UInt(32.W))
    val ctrl = Input(UInt(4.W))
    val enable = Input(Bool())
    val result = Output(UInt(32.W))
    val zero = Output(Bool())
  })

  val result = WireInit(0.U(32.W))
  when(io.enable) {
    switch(io.ctrl) {
      is("b0000".U) { result := io.a + io.b }
      is("b1000".U) { result := io.a - io.b }
      is("b0001".U) { result := io.a << io.b(4, 0) }
      is("b0010".U) { result := (io.a.asSInt < io.b.asSInt).asUInt }
      is("b0011".U) { result := (io.a < io.b).asUInt }
      is("b0100".U) { result := io.a ^ io.b }
      is("b0101".U) { result := io.a >> io.b(4, 0) }
      is("b1101".U) { result := (io.a.asSInt >> io.b(4, 0)).asUInt }
      is("b0110".U) { result := io.a | io.b }
      is("b0111".U) { result := io.a & io.b }
    }
  }

  io.result := result
  io.zero := result.orR
}
