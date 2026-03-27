import chisel3._
import chisel3.util._

class BaseDff[T <: Data](gen: T, supportFlush: Boolean = false) extends Module {
  val in  = IO(Flipped(Decoupled(gen)))
  val out = IO(Decoupled(gen))
  val flush = Option.when(supportFlush)(IO(Input(Bool())))

  val validReg = RegInit(false.B)
  val bitsReg  = RegInit(0.U.asTypeOf(chiselTypeOf(in.bits)))

  out.valid := validReg
  out.bits  := bitsReg

  in.ready := !validReg || out.ready

  when(flush.getOrElse(false.B)) {
    validReg := false.B
  }.elsewhen(in.ready) {
    validReg := in.valid
    when(in.valid) {
      bitsReg := in.bits
    }
  }
}
