package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// FetchBuffer（取指缓冲区）—— 环形缓冲区
// ============================================================================
// 入队：每周期最多 4 条指令（来自 Fetch 的 FetchPacket，含 BPU 预测信息）
// 出队：每周期最多 4 条指令（送给 4-wide Decode）
// 支持 flush：MEM 重定向或 Fetch 预测跳转时清空所有缓存的指令
//
// 核心作用：解耦 4-wide 取指与 4-wide 译码之间的时序差异
// ============================================================================
class FetchBuffer(val depth: Int = CPUConfig.fetchBufferEntries) extends Module {
  private val idxWidth = log2Ceil(depth)

  val enq = IO(Flipped(Decoupled(Vec(CPUConfig.fetchWidth, new FetchBufferEntry)))) // 入队端口：4 条指令（含预测信息）
  val deq = IO(Decoupled(Vec(CPUConfig.fetchWidth, new FetchBufferEntry))) // 出队端口： 4 条指令
  val flush = IO(Input(Bool())) // 清空信号

  // 环形缓冲区存储（每个 entry 包含指令、PC、预测信息）head/tail 指针比实际索引多 1 位，用最高位区分是否多绕了一圈
  val buffer = Reg(Vec(depth, new FetchBufferEntry))
  val head = RegInit(0.U((idxWidth + 1).W)) // 出队指针（最旧的指令）
  val tail = RegInit(0.U((idxWidth + 1).W)) // 入队指针（下一个写入位置）
  private def idx(ptr: UInt) = ptr(idxWidth - 1, 0)

  val count = tail - head                        // 当前缓冲区中有效指令数量
  val enqCount = PopCount(enq.bits.map(_.valid)) // 统计本次 FetchPacket 中有效指令数

  // 入队有效槽位偏移计算，确定写入缓冲区的位置
  val offsets = WireInit(VecInit(Seq.fill(4)(0.U(idxWidth.W))))   // 默认驱动为 0
  for (i <- 1 until 4) {
    offsets(i) := offsets(i - 1) +& Mux(enq.bits(i - 1).valid, 1.U, 0.U)
  }

  // ---- 出队逻辑：每周期最多出队 4 条指令，若缓冲区中不足 4 条指令则出剩余指令 ----
  val deqCount = Mux(count >= 4.U, 4.U(3.W), count(2, 0)) // 实际出队数量 = min(count, 4)
  for (i <- 0 until CPUConfig.fetchWidth) { // 读出 4 个槽位的数据和有效掩码
    deq.bits(i)       := buffer(idx(head) +% i.U)
    deq.bits(i).valid := i.U < deqCount // 指令数量不足的槽位设置为无效，优先填满 0, 1, 2, 3
  }

  // ---- v15 修复 (algo_array_ops_ooo4 死锁根因)：限制本拍出队的"预测型分支" ≤ 2 ----
  // Rename 阶段的 BCT 每拍最多保存 saveW=2 个 checkpoint。当 4-wide bundle 中存在 ≥3
  // 个 bType/JALR 预测分支时，第 3+ 个分支会因 PriorityEncoderOH 仅识别前 2 个而被
  // 误判为 hasCheckpoint=false / checkpointIdx=0，但仍被分配进 ROB。它日后若发生
  // mispredict，Memory 会以 ckptIdx=0 作 recoverIdx 触发"幻影 recover"——污染 BCT
  // 状态、最终导致 BCT 永久 full，处理器死锁。
  // 修复：在 FB 出队侧检测预测分支数量，若 >2 则把出队范围截断到第 2 个分支（含），
  // 第 3+ 个分支推迟到下一拍再出队。下一拍它们位于新的 4-lane 窗口低位，
  // 又会成为该拍的第 1/2 个预测分支，可正常保存 checkpoint。
  val deqIsPredBranch = Wire(Vec(CPUConfig.fetchWidth, Bool()))
  for (i <- 0 until CPUConfig.fetchWidth) {
    val instI  = buffer(idx(head) +% i.U).inst
    val opcode = instI(6, 0)
    val isBType = opcode === "b1100011".U  // RV32I B-type 分支
    val isJalr  = opcode === "b1100111".U  // RV32I JALR
    deqIsPredBranch(i) := (i.U < deqCount) && (isBType || isJalr)
  }
  val deqBranchOH         = deqIsPredBranch.asUInt
  val firstBrOH           = PriorityEncoderOH(deqBranchOH)
  val afterFirstBranchOH  = deqBranchOH & ~firstBrOH
  val secondBrOH          = PriorityEncoderOH(afterFirstBranchOH)
  val afterSecondBranchOH = afterFirstBranchOH & ~secondBrOH
  val hasThirdBranch      = afterSecondBranchOH.orR
  val secondBrIdx         = OHToUInt(secondBrOH)
  // 当出现 ≥3 个预测分支时，把出队数量截断到第 2 个分支所在 lane（含）
  val truncatedDeqCount   = Mux(hasThirdBranch, secondBrIdx +& 1.U, deqCount)
  for (i <- 0 until CPUConfig.fetchWidth) {
    deq.bits(i).valid := i.U < truncatedDeqCount
  }

  when(flush) { // flush 时重置所有状态
    head := 0.U
    tail := 0.U
  }.otherwise {
    when(enq.fire && !flush) { // 入队使能（flush 时不执行）：将有效指令（含预测信息）写入缓冲区
      for (i <- 0 until CPUConfig.fetchWidth) {
        when(enq.bits(i).valid) {
          val writeIdx = idx(tail) +% offsets(i)
          buffer(writeIdx) := enq.bits(i)
        }
      }
      tail := tail + enqCount
    }
    when(deq.fire && !flush) { // 出队使能（flush 时不执行）：移动头指针，按截断后的实际出队数推进
      head := head + truncatedDeqCount
    }
  }

  // ---- 出入队信号：空闲槽位足够时才接受入队；缓冲区非空时可出队 ----
  enq.ready := depth.U - count >= enqCount
  deq.valid := count > 0.U
}
