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

  val enq   = IO(Flipped(Decoupled(new DispatchPacket))) // 入队端口：最多 4 条指令，优先占据低位
  val flush = IO(Input(Bool()))                          // 清空信号（Memory 重定向时使用）

  // ---- 出队端口（4-wide）----
  val deqCount = IO(Input(UInt(3.W))) // Issue 阶段通过 deqCount 告诉 IssueQueue 本周期实际消费了几条
  val deq = IO(Output(Vec(4, new DispatchEntry))) // valid(i) 指示第 i 路是否有有效指令可供 Issue 选择

  // 环形缓冲区本体
  val buffer = Reg(Vec(depth, new DispatchEntry))

  // head/tail 指针比实际索引多 1 位，用最高位区分是否多绕了一圈（满/空判断）
  val head = RegInit(0.U((idxWidth + 1).W))   // 出队指针（最旧的指令）
  val tail = RegInit(0.U((idxWidth + 1).W))   // 入队指针（下一个写入位置）
  private def idx(ptr: UInt) = ptr(idxWidth - 1, 0)
  val count = tail - head            // 当前缓冲区中有效指令数量

  val enqCount = enq.bits.validCount // 本次 DispatchPacket 中有效指令数

  // 预计算每个入队槽位的累计偏移（用于确定写入缓冲区的位置）
  val offsets = WireInit(VecInit(Seq.fill(4)(0.U(idxWidth.W))))
  for (i <- 1 until 4) {
    offsets(i) := offsets(i - 1) +& Mux(enq.bits.entries(i - 1).valid, 1.U, 0.U)
  }

  when(flush) { // flush 时重置所有状态，清空缓冲区
    head := 0.U
    tail := 0.U
  }.otherwise {
    when(enq.fire && !flush) { // 入队：将有效指令写入缓冲区
      for (i <- 0 until 4) {
        when(enq.bits.entries(i).valid) {
          val writeIdx = idx(tail) +% offsets(i)
          buffer(writeIdx) := enq.bits.entries(i)
        }
      }
      tail := tail + enqCount
    }
    head := head + Mux(flush, 0.U, deqCount) // 出队：按 Issue 通知的实际出队数量移动头指针
  }

  // ---- 出队数据驱动：将队列头部最多 4 条指令暴露给 Issue ----
  for (i <- 0 until 4) {
    deq(i)       := buffer(idx(head + i.U))
    deq(i).valid := count > i.U
  }

  // ---- 入队握手 ----
  enq.ready := (depth.U - count) >= enqCount // 入队条件：空闲槽位足够容纳本次入队的指令数
}
