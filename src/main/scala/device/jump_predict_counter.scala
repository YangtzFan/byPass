import chisel3._

class jump_predict_counter extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(6.W))
    val d = Input(UInt(2.W))
    val we = Input(Bool())
    val spo = Output(UInt(2.W))
  })

  val mem = RegInit(VecInit(Seq.fill(64)(0.U(2.W))))
  io.spo := mem(io.a)
  when(io.we) {
    mem(io.a) := io.d
  }
}
