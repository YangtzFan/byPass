package mycpu.dataLane

import chisel3._
import chisel3.util._

// ============================================================================
// BaseDff —— 通用流水线寄存器（使用 Decoupled 握手协议）
// ============================================================================
// 功能：在相邻流水级之间缓存数据，使用 valid/ready 反压握手：
//   - in.valid  : 上游数据有效
//   - in.ready  : 本级可以接收（当前无有效数据 或 下游已取走）
//   - out.valid : 本级有有效数据等待下游取走
//   - out.ready : 下游可以接收
//
// 参数：
//   gen          : 数据类型（如 FetchBufferEntry、DecodeOut 等 Bundle）
//   supportFlush : 是否需要 flush 端口（flush 时 valid 清零，丢弃数据）
// ============================================================================
class BaseDff[T <: Data](gen: T, supportFlush: Boolean = false) extends Module {
  val in  = IO(Flipped(Decoupled(gen)))   // 输入端（Flipped 使 valid/bits 为 Input，ready 为 Output）
  val out = IO(Decoupled(gen))             // 输出端（valid/bits 为 Output，ready 为 Input）
  val flush = Option.when(supportFlush)(IO(Input(Bool())))  // 可选的 flush 信号

  val validReg = RegInit(false.B)                            // 数据是否有效
  val bitsReg  = RegInit(0.U.asTypeOf(chiselTypeOf(in.bits))) // 缓存的数据

  out.valid := validReg
  out.bits  := bitsReg

  // 背压逻辑：当前为空 或 下游已取走 → 可以接收新数据
  in.ready := !validReg || out.ready

  when(flush.getOrElse(false.B)) {
    // flush 时清除 valid，丢弃当前缓存的数据
    validReg := false.B
  }.elsewhen(in.ready) {
    // 可以接收时，用上游 valid 更新本级 valid
    validReg := in.valid
    when(in.valid) {
      bitsReg := in.bits   // 锁存新数据
    }
  }
}
