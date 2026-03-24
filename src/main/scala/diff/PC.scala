import chisel3._
import chisel3.util.RegEnable

class PC extends Module {
  val io = IO(new Bundle {
    val jump_predict_addr = Input(UInt(32.W))
    val pipeline_flush_addr = Input(UInt(32.W))
    val jump_enable = Input(Bool())
    val pipeline_flush_enable = Input(Bool())
    val if_hold_dff = Input(UInt(3.W))
    val pc_o = Output(UInt(32.W))
  })

  val pcLden = Mux(io.pipeline_flush_enable, true.B, !(io.if_hold_dff > 1.U))
  val pc = RegInit(0.U(32.W))
  val pcNext = Mux(io.jump_enable, Mux(io.pipeline_flush_enable, io.pipeline_flush_addr, io.jump_predict_addr), pc + 4.U)

  when(pcLden) { pc := pcNext }
  io.pc_o := pc
}
