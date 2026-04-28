package mycpu.device

import chisel3._
import chisel3.util._

// ============================================================================
// BTB（Branch Target Buffer）—— 为 JALR 提供间接跳转目标预测
// ============================================================================
// 为什么需要 BTB：
//   - JALR 的目标依赖 rs1，Fetch 阶段无法直接算出，传统实现总是预测“不跳转”，
//     导致每条 JALR 到了 Execute 必然触发 mispredict redirect，浪费 4~6 拍。
//   - BTB 按 PC 缓存“上一次该 JALR 解析到的实际目标”；相同调用点大概率
//     命中（函数返回、跳表等都高度稳定）。
//
// 设计参数：
//   - 直接映射 entries = 32；按 PC 低位索引；tag 保存 PC 剩余高位；
//   - 4 个读端口（Fetch 一拍 4 条指令并行查询）；1 个写端口（Execute 解析 JALR 后更新）；
//   - valid bit 初始 0；命中条件 valid && tag_match。
// ============================================================================
class BTB(entries: Int = 32, readPorts: Int = 4) extends Module {
  val idxWidth = log2Ceil(entries)
  // PC 位宽 32；低 2 位恒为 0 不进 tag/idx。
  val tagWidth = 32 - idxWidth - 2

  val io = IO(new Bundle {
    // ---- 读端口（Fetch 查询）----
    val read_pc = Input(Vec(readPorts, UInt(32.W)))
    val hit     = Output(Vec(readPorts, Bool()))
    val target  = Output(Vec(readPorts, UInt(32.W)))

    // ---- 写端口（Execute 更新）----
    val update_valid  = Input(Bool())
    val update_pc     = Input(UInt(32.W))
    val update_target = Input(UInt(32.W))
  })

  // BTB 存储：三组并行向量寄存器
  val validTable  = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val tagTable    = RegInit(VecInit(Seq.fill(entries)(0.U(tagWidth.W))))
  val targetTable = RegInit(VecInit(Seq.fill(entries)(0.U(32.W))))

  // 取 PC 中对应的 idx / tag 片段
  private def idxOf(pc: UInt): UInt = pc(idxWidth + 1, 2)
  private def tagOf(pc: UInt): UInt = pc(31, idxWidth + 2)

  // ---- 读端口：组合输出命中与目标 ----
  for (i <- 0 until readPorts) {
    val r = idxOf(io.read_pc(i))
    io.hit(i)    := validTable(r) && (tagTable(r) === tagOf(io.read_pc(i)))
    io.target(i) := targetTable(r)
  }

  // ---- 写端口：命中后无条件更新（直接映射覆盖）----
  when(io.update_valid) {
    val w = idxOf(io.update_pc)
    validTable(w)  := true.B
    tagTable(w)    := tagOf(io.update_pc)
    targetTable(w) := io.update_target
  }
}
