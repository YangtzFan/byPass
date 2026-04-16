package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// Rename（重命名阶段）—— 无寄存器重命名版本
// ============================================================================
// 本版本不进行任何寄存器重命名操作，仅完成两件事：
//   1. 将 DecRenDff 输入的 4 条 DecodedInst 透传到 RenDisDff
//   2. 计算 validCount（有效指令数）和 storeCount（有效 Store 指令数）
//      供 Dispatch 阶段进行 ROB/StoreBuffer/IssueQueue 分配使用
//
// 流水线位置：DecRenDff → [Rename] → RenDisDff → Dispatch
// ============================================================================
class Rename extends Module {
  val in  = IO(Flipped(Decoupled(Vec(4, new DecodedInst)))) // 来自 DecRenDff 的 4 条译码结果
  val out = IO(Decoupled(new Rename_Dispatch_Payload))      // 输出到 RenDisDff
  val flush = IO(Input(Bool()))                             // 流水线冲刷信号

  // ---- 直接透传 4 条译码指令信息（无重命名处理）----
  for (i <- 0 until 4) {
    out.bits.entries(i) := in.bits(i)
  }

  // ---- 计算有效指令数（供 Dispatch 进行资源分配）----
  out.bits.validCount := PopCount(in.bits.map(_.valid))

  // ---- 计算有效 Store 指令数（供 Dispatch 进行 StoreBuffer 分配）----
  out.bits.storeCount := PopCount(
    in.bits.map(inst => inst.valid && inst.type_decode_together(2)) // type_decode_together(2) = sType
  )

  // ---- 握手信号 ----
  // 无寄存器重命名，不需要 FreeList/BCT 等资源检查，直接透传反压
  in.ready  := out.ready
  out.valid := in.valid && !flush // flush 时抑制输出，丢弃错误路径指令
}
