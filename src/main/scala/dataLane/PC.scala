package mycpu.dataLane

import chisel3._
import chisel3.util._

class PC extends Module {
  val in = IO(new Bundle {
    val jump_predict_addr     = Input(UInt(32.W))
    val pipeline_flush_addr   = Input(UInt(32.W))
    val jump_enable           = Input(Bool())
    val pipeline_flush_enable = Input(Bool())
  })
  val out = IO(Decoupled(UInt(32.W))) // PC 作为 source，只对外提供 Decoupled 输出

  val pcReg = RegInit(0.U(32.W))

  out.valid := true.B // 在五级流水线中 PC 源头通常“总有一个地址可发”
  out.bits := pcReg

  val redirectValid = in.pipeline_flush_enable || in.jump_enable
  val redirectPc = Mux(in.pipeline_flush_enable, in.pipeline_flush_addr, in.jump_predict_addr)

  when (in.pipeline_flush_enable) {
    pcReg := in.pipeline_flush_addr
  } .elsewhen (in.jump_enable) {
    pcReg := in.jump_predict_addr
  } .elsewhen (out.fire) {
    pcReg := pcReg + 4.U
  }
}