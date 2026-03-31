package mycpu.memory

import chisel3._
import chisel3.util._

// ============================================================================
// DRAM —— 数据 RAM（单端口，32 位宽）
// ============================================================================
// 用于 Load/Store 指令访问数据存储器
// 读取为组合逻辑（地址变化后立即输出数据）
// 写入在时钟上升沿执行
//
// 注意：实际的字节/半字 Load/Store 对齐和符号扩展
//       由 dram_driver 模块处理，DRAM 本身只做 32 位字级读写
// ============================================================================
class DRAM extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(16.W))      // 字地址（2^16 = 64K 个字 → 256KB 空间）
    val d   = Input(UInt(32.W))      // 写入数据
    val we  = Input(Bool())          // 写使能
    val spo = Output(UInt(32.W))     // 组合读出数据
  })

  val mem = Mem(1 << 16, UInt(32.W))  // 64K x 32-bit 存储阵列
  io.spo := mem(io.a)                 // 组合读
  when(io.we) {
    mem.write(io.a, io.d)             // 写入（上升沿有效）
  }
}
