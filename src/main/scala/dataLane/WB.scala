import chisel3._
import chisel3.util._

class WB extends Module {
  val in = IO(Flipped(Decoupled(new MEM_WB_Payload)))
  val io = IO(new Bundle {
    val inst_bubble_ifornot = Output(Bool())
    val inst_addr = Output(UInt(32.W))
    val inst_rd = Output(UInt(5.W))
    val data = Output(UInt(32.W))
    val reg_wen = Output(Bool())
  })

  val uType = in.bits.type_decode_together(8)
  val jal = in.bits.type_decode_together(7)
  val jalr = in.bits.type_decode_together(6)
  val lType = in.bits.type_decode_together(4)
  val iType = in.bits.type_decode_together(3)
  val rType = in.bits.type_decode_together(1)
  val other = in.bits.type_decode_together(0)

  // 必须用 in.valid 门控所有输出：当流水线中有气泡（valid=false）时，
  // BaseDff 的 bitsReg 仍保留旧数据，不检查 valid 会输出过时的指令信息
  io.inst_bubble_ifornot := in.valid && in.bits.type_decode_together(8, 0).orR
  io.inst_addr := in.bits.inst_addr
  io.inst_rd := in.bits.inst_rd
  io.data := in.bits.data
  io.reg_wen := in.valid && (uType || jal || jalr || lType || iType || rType)

  in.ready := true.B
}
