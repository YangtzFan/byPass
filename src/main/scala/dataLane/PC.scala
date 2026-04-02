package mycpu.dataLane

import chisel3._
import chisel3.util._

// ============================================================================
// PC（程序计数器）
// ============================================================================
// 负责生成下一个取指地址，优先级从高到低：
//   1. MEM 阶段重定向（分支预测错误，跳转到正确地址）—— 最高优先级
//   2. Fetch 阶段 BPU 预测跳转（JAL/B-type 预测 Taken）
//   3. 正常递增（+16 字节，因为 4-wide 取指每次取 4 条指令）
// ============================================================================
class PC extends Module {
  val in = IO(new Bundle {
    val fetch_predict_addr    = Input(UInt(32.W))   // Fetch BPU 预测的跳转目标地址
    val fetch_predict_enable  = Input(Bool())        // Fetch BPU 预测跳转使能
    val mem_redirect_addr     = Input(UInt(32.W))   // MEM 阶段重定向的正确地址
    val mem_redirect_enable   = Input(Bool())        // MEM 阶段重定向使能
  })
  val out = IO(Decoupled(UInt(32.W)))                // 输出当前 PC 值

  val pcReg = RegInit(0.U(32.W))  // PC 寄存器，复位为 0

  out.valid := true.B             // PC 始终有效
  out.bits := pcReg

  // 优先级选择：MEM redirect > Fetch predict > 正常递增
  when (in.mem_redirect_enable) {
    pcReg := in.mem_redirect_addr                    // 最高优先：MEM 重定向
  } .elsewhen (in.fetch_predict_enable) {
    pcReg := in.fetch_predict_addr                   // 次优先：Fetch BPU 预测跳转
  } .elsewhen (out.fire) {
    // 正常递增：+16 字节（4 条指令 × 4 字节/条）
    pcReg := (pcReg(31, 4) +% 1.U) ## 0.U(4.W)
  }
}