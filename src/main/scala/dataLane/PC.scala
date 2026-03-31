package mycpu.dataLane

import chisel3._
import chisel3.util._

// ============================================================================
// PC（程序计数器）
// ============================================================================
// 负责生成下一个取指地址，优先级从高到低：
//   1. Commit flush（分支预测错误，跳转到正确地址）
//   2. Decode 预测跳转（JAL/分支预测 Taken）
//   3. 正常递增（+16 字节，因为 4-wide 取指每次取 4 条指令）
// ============================================================================
class PC extends Module {
  val in = IO(new Bundle {
    val jump_predict_addr     = Input(UInt(32.W))   // Decode 预测的跳转目标地址
    val pipeline_flush_addr   = Input(UInt(32.W))   // Commit flush 的正确地址
    val jump_enable           = Input(Bool())        // Decode 预测跳转使能
    val pipeline_flush_enable = Input(Bool())        // Commit flush 使能
  })
  val out = IO(Decoupled(UInt(32.W)))                // 输出当前 PC 值

  val pcReg = RegInit(0.U(32.W))  // PC 寄存器，复位为 0

  out.valid := true.B             // PC 始终有效
  out.bits := pcReg

  // 优先级选择：flush > 预测跳转 > 正常递增
  when (in.pipeline_flush_enable) {
    pcReg := in.pipeline_flush_addr              // 最高优先：Commit flush
  } .elsewhen (in.jump_enable) {
    pcReg := in.jump_predict_addr                // 次优先：Decode 预测跳转
  } .elsewhen (out.fire) {
    // 正常递增：+16 字节（4 条指令 × 4 字节/条）
    pcReg := (pcReg(31, 4) +% 1.U) ## 0.U(4.W)
  }
}