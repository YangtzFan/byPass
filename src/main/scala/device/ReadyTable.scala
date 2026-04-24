package mycpu.device

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// ReadyTable —— 物理寄存器就绪状态表
// ============================================================================
// 128 位表，每位表示对应物理寄存器的值是否已经计算得出（ready）。
// 初始状态：全部 ready（p0~p31 对应初始架构寄存器映射，p32~p127 空闲但 ready 不影响）
//
// 操作：
//   - Rename 阶段：新分配的 pdst 置为 busy（not ready），因为执行结果还没算出来
//   - Refresh 阶段：执行结果写回时，标记 pdst 为 ready
//   - 分支恢复：从 checkpoint 恢复 ReadyTable 状态
//
// p0 永远 ready（p0 = 0，永远有效）
//
// 读端口（8 个）：Rename 阶段查询 psrc1/psrc2 是否就绪（用于 IssueQueue wakeup）
// 写端口（设计为 set/clear）：
//   - Rename 阶段最多 4 个 clear（置 busy）
//   - Refresh 阶段 1 个 set（置 ready）
// ============================================================================
class ReadyTable extends Module {
  val io = IO(new Bundle {
    // ---- 读端口（8 个，Rename 阶段查询源操作数就绪状态）----
    val raddr = Input(Vec(8, UInt(CPUConfig.prfAddrWidth.W)))    // 查询的物理寄存器编号
    val rdata = Output(Vec(8, Bool()))                           // 就绪状态（true = ready）

    // ---- Rename 阶段置 busy（新分配的 pdst 还没有执行结果）----
    val busyVen  = Input(Vec(4, Bool()))                         // busy 使能
    val busyAddr = Input(Vec(4, UInt(CPUConfig.prfAddrWidth.W))) // 待置 busy 的物理寄存器编号

    // ---- Refresh 阶段置 ready（执行结果已写回 PRF）----
    // 每个 refresh lane 一个独立的 (ven, addr) 端口。乱序下同拍不会出现重复 addr。
    val readyVen  = Input(Vec(CPUConfig.refreshWidth, Bool()))                                // 每 lane ready 使能
    val readyAddr = Input(Vec(CPUConfig.refreshWidth, UInt(CPUConfig.prfAddrWidth.W)))        // 每 lane 待置 ready 的物理寄存器编号

    // ---- 批量恢复端口（分支预测失败时恢复）----
    val recover     = Input(Bool())                              // 恢复使能
    val recoverData = Input(Vec(CPUConfig.prfEntries, Bool()))   // 恢复数据（完整 ReadyTable 快照）

    // ---- Checkpoint 读取端口 ----
    val snapData = Output(Vec(CPUConfig.prfEntries, Bool()))     // 当前完整 ReadyTable 快照
  })

  // 就绪状态表：初始全部 ready
  val table = RegInit(VecInit(Seq.fill(CPUConfig.prfEntries)(true.B)))

  // ---- 批量恢复逻辑 ----
  // 注意：恢复与 Refresh 同拍时，Refresh 来自比误预测分支更老的指令（位于 Memory/Refresh 级），
  // 必须与 recover 合流，否则会丢失"同拍就绪"位，导致 IQ 内依赖该 pdst 的老指令永远等待。
  // 多 refresh lane 时任一 lane 命中即视为置 ready（OR 合流）。
  when(io.recover) {
    for (i <- 0 until CPUConfig.prfEntries) {
      val setByRefresh = (0 until CPUConfig.refreshWidth).map { k =>
        io.readyVen(k) && (io.readyAddr(k) =/= 0.U) && (io.readyAddr(k) === i.U)
      }.reduce(_ || _)
      table(i) := io.recoverData(i) || setByRefresh
    }
  }.otherwise {
    // ---- Rename 阶段：新分配的 pdst 置 busy ----
    // p0 永远 ready，不能被置 busy
    for (i <- 0 until 4) {
      when(io.busyVen(i) && io.busyAddr(i) =/= 0.U) {
        table(io.busyAddr(i)) := false.B
      }
    }

    // ---- Refresh 阶段：执行结果写回后置 ready ----
    // 多 lane Refresh：每个 lane 分别置位；Refresh 的写优先级高于 Rename 的 busy
    // （同一物理寄存器同拍 Rename busy + 任一 lane Refresh ready → ready 生效）。
    for (k <- 0 until CPUConfig.refreshWidth) {
      when(io.readyVen(k) && io.readyAddr(k) =/= 0.U) {
        table(io.readyAddr(k)) := true.B
      }
    }
  }

  // ---- 组合读端口 ----
  // p0 始终返回 ready
  for (i <- 0 until 8) {
    io.rdata(i) := Mux(io.raddr(i) === 0.U, true.B, table(io.raddr(i)))
  }

  // ---- Checkpoint 快照输出 ----
  io.snapData := table
}
