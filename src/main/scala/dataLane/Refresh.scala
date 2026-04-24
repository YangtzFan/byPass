package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// Refresh（刷新阶段，原 WB）—— 更新 ROB 完成状态和结果字段
// ============================================================================
// 注意：这里 **不** 直接写寄存器堆，也 **不** 更新架构状态！
// Refresh 的职责是通知 ROB "这条指令已经执行完毕"，并将结果写入 ROB 表项。
// 真正写入寄存器堆和内存的操作在 Commit 阶段完成（保证顺序提交语义）。
//
// 无 flush 端口：Memory 重定向时，Refresh 中的指令必然比误预测指令更老（正确路径），
// 或者就是误预测指令本身（需要正常完成以便 Commit），因此不需要冲刷。
// ============================================================================
class Refresh extends Module {
  val in = IO(Flipped(Decoupled(new Memory_Refresh_Payload)))  // 输入：来自 MemRefDff 流水线寄存器

  // ---- ROB 完成标记接口 ----
  val robRefresh = IO(new ROBRefreshIO)

  // ---- 阶段 2 lane 访问别名（refreshWidth=1，仅使用 lanes(0)）----
  val inL = in.bits.lanes(0)

  // ROB 完成：组合逻辑直接透传
  robRefresh.valid          := in.valid && in.bits.validMask(0)
  robRefresh.idx            := inL.robIdx
  robRefresh.regWBData      := inL.data
  robRefresh.pdst           := inL.pdst           // 物理目的寄存器（PRF 写入 + ReadyTable 标记 ready）
  robRefresh.regWriteEnable := inL.regWriteEnable // 是否需要写回

  in.ready := true.B  // Refresh 始终准备好接收
}
