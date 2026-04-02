package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig
import mycpu.device.SE

// ============================================================================
// Decode（译码阶段）—— 4-wide 并行译码
// ============================================================================
// 从 FetchBuffer 取出最多 4 条指令，并行解码出操作类型、立即数等信息。
// 本模块为纯组合逻辑（无内部状态），分支预测已移至 Fetch 阶段，
// Load-Use 冒险检测已移至 Dispatch 阶段。
//
// 内部实例化 4 个 SE（符号扩展/立即数生成）模块，每路独立工作。
// ============================================================================
class Decode extends Module {

  val in  = IO(Flipped(Decoupled(new FetchBufferPacket)))  // 来自 FetchBuffer 的 4 条指令
  val out = IO(Decoupled(new Decode_Rename_Payload))       // 输出 4 条译码结果
  val flush = IO(Input(Bool()))                            // 流水线冲刷信号

  // 实例化 4 个符号扩展/立即数生成模块
  val ses = Seq.fill(4)(Module(new SE))

  for (i <- 0 until 4) {
    val entry = in.bits.entries(i)
    val inst  = entry.inst

    // ---- 指令字段拆解（RISC-V 标准格式）----
    val opcode = inst(6, 0)
    val rd     = inst(11, 7)
    val funct3 = inst(14, 12)
    val rs1    = inst(19, 15)
    val rs2    = inst(24, 20)
    val funct7 = inst(31, 25)
    val imm12  = Cat(funct7, rs2)        // 12 位立即数原始字段
    val imm20  = Cat(funct7, rs2, rs1, funct3) // 20 位立即数原始字段

    // ---- 指令类型识别 ----
    val uType = (opcode === "b0110111".U) || (opcode === "b0010111".U) // LUI / AUIPC
    val jal   = opcode === "b1101111".U    // JAL
    val jalr  = opcode === "b1100111".U    // JALR
    val bType = opcode === "b1100011".U    // B 型分支指令
    val lType = opcode === "b0000011".U    // Load 指令
    val iType = opcode === "b0010011".U    // I 型算术/逻辑指令
    val sType = opcode === "b0100011".U    // Store 指令
    val rType = opcode === "b0110011".U    // R 型算术/逻辑指令
    val other = (opcode === "b0001111".U) || (opcode === "b1110011".U) // FENCE / ECALL

    // 9 位独热编码：{uType, jal, jalr, bType, lType, iType, sType, rType, other}
    val type_decode_together = Cat(uType, jal, jalr, bType, lType, iType, sType, rType, other)

    // ---- 立即数生成 ----
    ses(i).io.type_decode_together := type_decode_together
    ses(i).io.imm12  := imm12
    ses(i).io.imm20  := imm20
    ses(i).io.funct7 := funct7
    ses(i).io.rd     := rd

    // ---- 输出单条译码结果 ----
    out.bits.insts(i).pc                   := entry.pc
    out.bits.insts(i).inst                 := inst
    out.bits.insts(i).imm                  := ses(i).io.imm_o
    out.bits.insts(i).type_decode_together := type_decode_together
    out.bits.insts(i).predict_taken        := entry.predict_taken   // 从 Fetch BPU 传递
    out.bits.insts(i).predict_target       := entry.predict_target
    out.bits.insts(i).bht_meta             := entry.bht_meta
  }

  // 有效指示位直接透传 FetchBuffer 的出队掩码
  out.bits.valid := in.bits.valid

  // Decode 是纯组合逻辑，直接透传 valid/ready，flush 时抑制输出
  in.ready  := out.ready
  out.valid := in.valid && !flush
}
