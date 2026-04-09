package mycpu.device

import chisel3._
import chisel3.util._

// 2-bit 饱和计数器分支历史表 (Branch History Table)
// 使用格雷码状态机编码，相邻状态仅 1 bit 翻转，降低翻转功耗、改善时序
//   SNT(00) ←→ WNT(01) ←→ WT(11) ←→ ST(10)
//   predict_taken = counter[1]（高位为 1 表示预测跳转，即 WT/ST 状态）
// 支持 4 个并行读端口（供 Fetch 阶段 4-wide BPU 使用）和 1 个写端口（Execute 更新）
class BHT(entries: Int = 64, readPorts: Int = 4) extends Module {
  val idxWidth = log2Ceil(entries)

  val io = IO(new Bundle {
    // 读端口：Fetch 阶段用每条指令的 PC 索引查询预测结果（4 路并行）
    val read_idx      = Input(Vec(readPorts, UInt(idxWidth.W)))
    val predict       = Output(Vec(readPorts, UInt(2.W)))   // 2-bit 格雷码计数器值

    // 写端口：Execute 阶段用分支实际结果更新计数器
    val update_valid  = Input(Bool())
    val update_idx    = Input(UInt(idxWidth.W))
    val update_taken  = Input(Bool())   // 实际是否跳转
  })

  val table = RegInit(VecInit(Seq.fill(entries)(1.U(2.W)))) // 初始化为 01 (WNT: Weakly Not-Taken，格雷码)

  // 读：每路输出 2-bit 格雷码值，高位 bit[1] = 1 则预测 taken (WT=11 或 ST=10)
  for (i <- 0 until readPorts) {
    io.predict(i) := table(io.read_idx(i))
  }

  // 写：格雷码状态转移（纯组合逻辑，无加减法器）
  when(io.update_valid) {
    val cur = table(io.update_idx)
    table(io.update_idx) := Mux(io.update_taken,
      Cat(cur(1) | cur(0), !cur(1)),     // taken: SNT→WNT→WT→ST（向 ST 方向移动）
      Cat(cur(1) & !cur(0), cur(1)))     // not-taken: ST→WT→WNT→SNT（向 SNT 方向移动）
  }
}
