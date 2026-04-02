package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// RenameBuffer（重命名缓冲区）—— 环形缓冲区
// ============================================================================
// 入队：每周期最多 4 条指令（来自 Rename 的 RenamePacket）
// 出队：每周期 1 条指令（送给 Dispatch，按程序顺序 FIFO 出队）
// 支持 flush：MEM 重定向时清空所有缓存的指令
//
// 核心作用：解耦 4-wide Rename 与 1-wide Dispatch 之间的带宽差异
// ============================================================================
class RenameBuffer(val depth: Int = CPUConfig.renameBufferEntries) extends Module {
  private val idxWidth = log2Ceil(depth)

  val enq   = IO(Flipped(Decoupled(new RenamePacket))) // 入队端口：最多 4 条指令
  val deq   = IO(Decoupled(new RenameEntry))           // 出队端口：1 条指令
  val flush = IO(Input(Bool()))                        // 清空信号
  
  val buffer = Reg(Vec(depth, new RenameEntry)) // 环形缓冲区本体

  // head/tail 指针比实际索引多 1 位，用最高位区分是否多绕了一圈
  private val ptrWidth = idxWidth + 1
  val head = RegInit(0.U(ptrWidth.W))   // 出队指针（最旧的指令）
  val tail = RegInit(0.U(ptrWidth.W))   // 入队指针（下一个写入位置）
  private def idx(ptr: UInt) = ptr(idxWidth - 1, 0)

  val count = tail - head               // 当前缓冲区中有效指令数量
  val enqCount = enq.bits.count         // 本次 RenamePacket 中有效指令数

  // ---- 入队/出队的实际使能（flush 时不执行）----
  val actualEnq = enq.fire && !flush
  val actualDeq = deq.fire && !flush

  // 预计算每个槽位的累计偏移（用于确定写入位置）
  val offsets = Wire(Vec(4, UInt(idxWidth.W)))
  offsets(0) := 0.U
  for (i <- 1 until 4) {
    offsets(i) := offsets(i - 1) +& Mux(enq.bits.valid(i - 1), 1.U, 0.U)
  }

  when(flush) { // flush 时重置所有状态
    head := 0.U
    tail := 0.U
  }.otherwise {
    when(actualEnq) { // 入队：将有效指令写入缓冲区
      for (i <- 0 until 4) {
        when(enq.bits.valid(i)) {
          val writeIdx = idx(tail) +% offsets(i)
          buffer(writeIdx) := enq.bits.entries(i)
        }
      }
      tail := tail + enqCount
    }
    when(actualDeq) { // 出队：移动头指针
      head := head + 1.U
    }
  }

  // ---- 出入队：空闲槽位足够时才接受入队，缓冲区非空时即可出队 ----
  enq.ready := (depth.U - count) >= enqCount
  deq.valid := count > 0.U
  deq.bits  := buffer(idx(head))
}
