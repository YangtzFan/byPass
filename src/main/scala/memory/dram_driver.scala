package mycpu.memory

import chisel3._
import chisel3.util._

// ============================================================================
// dram_driver —— 数据存储器驱动（wstrb 字节级接口）
// ============================================================================
//   addr  : 18 位字节地址（addr[17:2] 用于 DRAM 字寻址）
//   wdata : 32 位字节对齐后的写数据（已由上游按字节位置摆好）
//   wstrb : 4 位字节写使能掩码（每位控制一个字节是否写入）
//   we    : 写使能
//   rdata : 32 位原始字读出数据（不做符号扩展，由 Memory 阶段处理）
//
// 写操作：根据 wstrb 逐字节合并 wdata 和 DRAM 原始数据
// 读操作：直接返回 DRAM 原始 32 位字（组合逻辑）
// ============================================================================
class dram_driver extends Module {
  val io = IO(new Bundle {
    val addr  = Input(UInt(18.W))  // 字节地址
    val wdata = Input(UInt(32.W))  // 字节对齐后的写数据
    val wstrb = Input(UInt(4.W))   // 字节写使能掩码
    val we    = Input(Bool())      // 写使能
    val rdata = Output(UInt(32.W)) // 原始字读出数据
  })

  // DRAM 实例
  val memDram = Module(new DRAM)
  val wordAddr = io.addr(17, 2) // 字对齐地址
  memDram.io.a  := wordAddr
  memDram.io.we := io.we
  val rawData = memDram.io.spo  // DRAM 当前字内容

  // ---- 读数据路径（直接返回原始 32 位字）----
  io.rdata := rawData

  // ---- 写数据路径（按 wstrb 逐字节合并）----
  val mergedBytes = Wire(Vec(4, UInt(8.W)))
  for (b <- 0 until 4) { // wstrb(b)=1 时用 wdata 对应字节，否则保持 DRAM 原始字节
    mergedBytes(b) := Mux(io.wstrb(b), io.wdata(b * 8 + 7, b * 8), rawData(b * 8 + 7, b * 8))
  }
  memDram.io.d := Cat(mergedBytes(3), mergedBytes(2), mergedBytes(1), mergedBytes(0))
}
