package mycpu.device

import chisel3._
import chisel3.util._

// ============================================================================
// ALU —— 32 位算术逻辑单元
// ============================================================================
// 支持 RV32I 所有 R/I 型运算指令
// ctrl 编码与 funct3 对齐（bit[3] 来自 funct7[5]，用于区分 ADD/SUB、SRL/SRA）
// ============================================================================
class ALU extends Module {
  val io = IO(new Bundle {
    val a      = Input(UInt(32.W))     // 操作数 A（通常为 rs1）
    val b      = Input(UInt(32.W))     // 操作数 B（rs2 或立即数）
    val ctrl   = Input(UInt(4.W))      // 控制信号（{funct7[5], funct3}）
    val enable = Input(Bool())         // 使能（无效时结果为 0）
    val result = Output(UInt(32.W))    // 运算结果
    val zero   = Output(Bool())        // 结果是否非零（注意：orR = 非零时为真）
  })

  val result = WireInit(0.U(32.W))
  when(io.enable) {
    switch(io.ctrl) {
      is("b0000".U) { result := io.a + io.b }                          // ADD / ADDI
      is("b1000".U) { result := io.a - io.b }                          // SUB
      is("b0001".U) { result := io.a << io.b(4, 0) }                   // SLL / SLLI（逻辑左移）
      is("b0010".U) { result := (io.a.asSInt < io.b.asSInt).asUInt }   // SLT / SLTI（有符号比较）
      is("b0011".U) { result := (io.a < io.b).asUInt }                 // SLTU / SLTIU（无符号比较）
      is("b0100".U) { result := io.a ^ io.b }                          // XOR / XORI
      is("b0101".U) { result := io.a >> io.b(4, 0) }                   // SRL / SRLI（逻辑右移）
      is("b1101".U) { result := (io.a.asSInt >> io.b(4, 0)).asUInt }   // SRA / SRAI（算术右移）
      is("b0110".U) { result := io.a | io.b }                          // OR / ORI
      is("b0111".U) { result := io.a & io.b }                          // AND / ANDI
    }
  }

  io.result := result
  io.zero   := result.orR   // 结果非零标志
}
