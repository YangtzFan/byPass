package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// IssueQueue（发射队列）—— 环形缓冲区，4-in / 4-out
// ============================================================================
// 入队：每周期最多 4 条指令（来自 Dispatch 的 DispatchPacket）
// 出队：硬件支持 4-out 宽度，由 Issue 模块通过 deqCount 信号控制每周期实际出队数量
//       当前版本 Issue 阶段为顺序单发射，每次出队 1 条指令
//       后续乱序四发射可扩展为每周期出队多条
// 支持 flush：Memory 重定向时清空所有缓存的指令
//
// 核心作用：解耦 4-wide Dispatch 与 Issue/Execute 后端之间的带宽差异
//           承担原 RenameBuffer 的缓冲职责
// ============================================================================
class IssueQueue(val depth: Int = CPUConfig.issueQueueEntries) extends Module {
  private val idxWidth = log2Ceil(depth)

  val enq   = IO(Flipped(Decoupled(new DispatchPacket)))   // 入队端口：最多 4 条指令（来自 Dispatch）
  val flush = IO(Input(Bool()))                            // 清空信号（Memory 重定向时使用）

  // ---- 出队端口（4-wide）----
  // entries(0..3) 依次对应队列头部的最多 4 条指令
  // valid(i) 指示第 i 路是否有有效指令可供 Issue 选择
  // Issue 阶段通过 deqCount 告诉 IssueQueue 本周期实际消费了几条
  val deq = IO(new Bundle {
    val entries  = Output(Vec(4, new DispatchEntry))     // 4 路出队数据
    val valid    = Output(Vec(4, Bool()))                 // 4 路有效位
    val deqCount = Input(UInt(3.W))                      // Issue 通知本周期实际出队数量（0-4）
    val ready    = Input(Bool())                          // 下游是否可以接收数据
  })

  // ---- 空闲槽位数量（供 Dispatch 查询，用于流控）----
  val freeCount = IO(Output(UInt((idxWidth + 1).W)))

  // 环形缓冲区本体
  val buffer = Reg(Vec(depth, new DispatchEntry))

  // head/tail 指针比实际索引多 1 位，用最高位区分是否多绕了一圈（满/空判断）
  private val ptrWidth = idxWidth + 1
  val head = RegInit(0.U(ptrWidth.W))   // 出队指针（最旧的指令）
  val tail = RegInit(0.U(ptrWidth.W))   // 入队指针（下一个写入位置）
  private def idx(ptr: UInt) = ptr(idxWidth - 1, 0)

  val count = tail - head                 // 当前缓冲区中有效指令数量
  val enqCount = enq.bits.count           // 本次 DispatchPacket 中有效指令数

  // 输出空闲槽位数量，供 Dispatch 阶段做流控判断
  freeCount := depth.U - count

  // ---- 入队/出队的实际使能（flush 时不执行）----
  val actualEnq = enq.fire && !flush
  val actualDeq = deq.deqCount > 0.U && deq.ready && !flush

  // 预计算每个入队槽位的累计偏移（用于确定写入缓冲区的位置）
  val offsets = Wire(Vec(4, UInt(idxWidth.W)))
  offsets(0) := 0.U
  for (i <- 1 until 4) {
    offsets(i) := offsets(i - 1) +& Mux(enq.bits.valid(i - 1), 1.U, 0.U)
  }

  when(flush) {
    // flush 时重置所有状态，清空缓冲区
    head := 0.U
    tail := 0.U
  }.otherwise {
    when(actualEnq) {
      // 入队：将有效指令写入缓冲区
      for (i <- 0 until 4) {
        when(enq.bits.valid(i)) {
          val writeIdx = idx(tail) +% offsets(i)
          buffer(writeIdx) := enq.bits.entries(i)
        }
      }
      tail := tail + enqCount
    }
    when(actualDeq) {
      // 出队：按 Issue 通知的实际出队数量移动头指针
      head := head + deq.deqCount
    }
  }

  // ---- 出队数据驱动：将队列头部最多 4 条指令暴露给 Issue ----
  for (i <- 0 until 4) {
    deq.entries(i) := buffer(idx(head + i.U))
    deq.valid(i)   := count > i.U
  }

  // ---- 入队握手 ----
  // 入队条件：空闲槽位足够容纳本次入队的指令数
  enq.ready := (depth.U - count) >= enqCount
}
