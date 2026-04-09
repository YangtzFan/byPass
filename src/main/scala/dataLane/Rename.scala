package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// Rename（重命名阶段）—— 4-wide 纯打拍占位级
// ============================================================================
// 当前实现为占位级，不进行真正的寄存器重命名（无 PRF/RAT），仅完成：
//   1. 计算 regWriteEnable（从 type_decode_together 提取）
//   2. 将 Decode_Rename_Payload 转换为 Rename_Dispatch_Payload 输出
//
// ROB 分配已移至 Dispatch 阶段。
// 后续版本将在此实现寄存器重命名（分配物理寄存器、更新 RAT 等）。
// ============================================================================
class Rename extends Module {
  val in  = IO(Flipped(Decoupled(Vec(4, new DecodedInst)))) // 来自 DecRenDff 的 4 条译码结果
  val out = IO(Decoupled(new Rename_Dispatch_Payload)) // 输出到 RenDisDff
  val flush = IO(Input(Bool()))                        // 流水线冲刷信号

  // ---- 逐路处理：生成 RenameEntry ----
  for (i <- 0 until 4) {
    val td    = in.bits(i).type_decode_together
    // 从 type_decode_together 提取指令类型
    val uType = td(8)
    val jal   = td(7)
    val jalr  = td(6)
    val lType = td(4)
    val iType = td(3)
    val sType = td(2)
    val rType = td(1)
    val regWriteEnable = uType || jal || jalr || lType || iType || rType // 是否需要写回寄存器（U、JAL、JALR、L、I、R）

    // ---- 向下游输出 RenameEntry ----
    out.bits.entries(i).pc                   := in.bits(i).pc
    out.bits.entries(i).inst                 := in.bits(i).inst
    out.bits.entries(i).imm                  := in.bits(i).imm
    out.bits.entries(i).type_decode_together := in.bits(i).type_decode_together
    out.bits.entries(i).predict_taken        := in.bits(i).predict_taken
    out.bits.entries(i).predict_target       := in.bits(i).predict_target
    out.bits.entries(i).bht_meta             := in.bits(i).bht_meta
    out.bits.entries(i).regWriteEnable       := regWriteEnable
    out.bits.entries(i).memWriteEnable       := sType            // 是否是 store 指令
    out.bits.entries(i).valid                := in.bits(i).valid // valid 直接透传
  }
  out.bits.validCount := PopCount(in.bits.map(_.valid)) // 有效指令数量
  out.bits.storeCount := PopCount((in.bits.map(_.valid) zip out.bits.entries.map(_.memWriteEnable)).map {
    case (valid, memWriteEnable) => valid && memWriteEnable
  }) // store类型且有效的指令数量

  // ---- 握手信号 ----
  // 纯打拍占位级，直接透传握手信号，flush 时抑制输出
  in.ready  := out.ready
  out.valid := in.valid && !flush
}
