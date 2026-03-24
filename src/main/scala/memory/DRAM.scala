import chisel3._
import chisel3.util._

class DRAM extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(16.W))
    val d = Input(UInt(32.W))
    val we = Input(Bool())
    val spo = Output(UInt(32.W))
  })

  val mem = Mem(1 << 16, UInt(32.W))
  io.spo := mem(io.a)
  when(io.we) {
    mem.write(io.a, io.d)
  }
}
