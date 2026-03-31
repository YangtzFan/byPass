package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// WB（写回阶段）—— 标记 ROB 表项完成
// ============================================================================
// 注意：这里 **不** 直接写寄存器堆！
// WB 的职责是通知 ROB “这条指令已经执行完毕”，并将结果写入 ROB 表项
// 真正写入寄存器堆的操作在 Commit 阶段完成（保证顺序提交语义）
// ============================================================================
class WB extends Module {
  val in = IO(Flipped(Decoupled(new MEM_WB_Payload)))  // 输入：来自 MEM/WB 流水线寄存器
  val flush = IO(Input(Bool()))                         // 全局 flush 信号

  // ---- ROB 完成标记接口（使用打包 Bundle）----
  val robWb = IO(new ROBWbIO)

  // ROB 完成：组合逻辑直接透传（与 MEM/WB 输出同周期）
  // flush 时不标记完成，防止错误路径的执行结果污染 ROB
  robWb.valid := in.valid && !flush
  robWb.idx := in.bits.robIdx
  robWb.result := in.bits.data
  robWb.actualTaken := in.bits.actual_taken
  robWb.actualTarget := in.bits.actual_target
  robWb.mispredict := in.bits.mispredict
  robWb.storeAddr := in.bits.store_addr
  robWb.storeData := in.bits.store_data
  robWb.storeMask := in.bits.store_mask

  in.ready := true.B  // WB 始终准备好接收
}
