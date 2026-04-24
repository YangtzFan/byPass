package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// Refresh（刷新阶段，原 WB）—— 多 lane 并行标记 ROB 完成
// ============================================================================
// 变更点（Phase 3+4 双发射）：
//   - robRefresh 变为 Vec(refreshWidth, ROBRefreshIO)，每 lane 独立
//   - 每 lane 的 valid 取决于该 lane 的 payload.validMask(k)
// Refresh 仍然 **不** 写寄存器堆、**不** 更新架构状态，
// 真正写 PRF / ReadyTable 的逻辑在 MyCPU 顶层按 lane 展开。
// ============================================================================
class Refresh extends Module {
  val in = IO(Flipped(Decoupled(new Memory_Refresh_Payload)))

  val robRefresh = IO(Vec(CPUConfig.refreshWidth, new ROBRefreshIO))

  for (k <- 0 until CPUConfig.refreshWidth) {
    val lane = in.bits.lanes(k)
    val valid = in.valid && in.bits.validMask(k)
    robRefresh(k).valid          := valid
    robRefresh(k).idx            := lane.robIdx
    robRefresh(k).regWBData      := lane.data
    robRefresh(k).pdst           := lane.pdst
    robRefresh(k).regWriteEnable := lane.regWriteEnable
  }

  in.ready := true.B
}
