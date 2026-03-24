import chisel3._

class jump_PHT extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(7.W))
    val d = Input(UInt(6.W))
    val we = Input(Bool())
    val spo = Output(UInt(6.W))
  })

  val mem = RegInit(VecInit(Seq.fill(128)(0.U(6.W))))
  io.spo := mem(io.a)
  when(io.we) {
    mem(io.a) := io.d
  }
}
