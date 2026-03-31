package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// Fetch（取指阶段）—— 4 路宽取指
// ============================================================================
// 每周期从 IROM 读取 128 位数据（4 条 32 位指令），打包成 FetchPacket 输出
//
// 地址对齐处理：PC 的低 4 位决定在 128 位数据中的位置：
//   - PC[1:0] 必须为 00（指令 4 字节对齐）
//   - PC[3:2] 表示在 4 条指令中的偏移，例如 PC[3:2]=2 表示从第 3 条开始有效
//   - 只有索引 >= PC[3:2] 的槽位才标记为有效（validMask）
// ============================================================================
class Fetch extends Module {
  val in = IO(Flipped(Decoupled(UInt(32.W)))) // 输入：PC 值
  val out = IO(Decoupled(new FetchPacket))    // 输出：取指结果包
  val io = IO(new Bundle {
    val inst_addr_o = Output(UInt(14.W))      // 输出到 IROM 的地址（128-bit 字地址 = PC[17:4]）
    val inst_i      = Input(UInt(128.W))      // IROM 返回的 128 位数据（4 条指令拼接）
  })

  val pc = in.bits                     // 当前 PC 值
  val pcWordIdx = pc(3, 2)             // 在 4 条指令中的偏移索引（0~3）

  // IROM 地址：128-bit 字索引 = PC[17:4]，14 位宽
  io.inst_addr_o := pc(17, 4)

  // 将 128 位数据拆分为 4 × 32 位指令
  // 存储布局：inst[0] 在 bits[31:0]，inst[1] 在 bits[63:32]，以此类推
  val insts = Wire(Vec(4, UInt(32.W)))
  for (i <- 0 until 4) {
    insts(i) := io.inst_i(32 * i + 31, 32 * i)
  }

  // 为 4 个槽位生成 PC 和有效掩码
  val basePC = pc(31, 4) ## 0.U(4.W)  // 16 字节对齐的基址址 PC
  for (i <- 0 until 4) {
    out.bits.pcs(i) := basePC + (i.U << 2)      // 每条指令的 PC = 基址 + i*4
    out.bits.insts(i) := insts(i)               // 对应的指令
    out.bits.validMask(i) := (i.U >= pcWordIdx) // 只有 >= 当前偏移的槽位才有效
  }

  in.ready := out.ready                // 反压：下游不接收时，PC 也暂停
  out.valid := in.valid                // PC 有效时，取指结果就有效
}
