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

  val enq = IO(Flipped(Decoupled(new FetchBufferPacket))) // 入队端口：4 条指令（含预测信息）
  val deq = IO(Decoupled(new FetchBufferPacket))       // 出队端口：最多 4 条指令
  val flush = IO(Input(Bool()))                        // 清空信号

  // 环形缓冲区存储（每个 entry 包含指令、PC、预测信息）
  val buffer = Reg(Vec(depth, new FetchBufferEntry))

  // head/tail 指针比实际索引多 1 位，用最高位区分是否多绕了一圈
  private val ptrWidth = idxWidth + 1
  val head = RegInit(0.U(ptrWidth.W))   // 出队指针（最旧的指令）
  val tail = RegInit(0.U(ptrWidth.W))   // 入队指针（下一个写入位置）
  private def idx(ptr: UInt) = ptr(idxWidth - 1, 0)

  val count = tail - head               // 当前缓冲区中有效指令数量
  val enqCount = PopCount(enq.bits.valid) // 本次 FetchPacket 中有效指令数

  // ---- 出队逻辑：每周期最多出队 4 条指令，若缓冲区中不足 4 条指令则出剩余指令 ----
  val deqCount = Mux(count >= 4.U, 4.U(3.W), count(2, 0)) // 实际出队数量 = min(count, 4)
  for (i <- 0 until 4) { // 读出 4 个槽位的数据和有效掩码
    deq.bits.entries(i) := buffer(idx(head) +% i.U)
    deq.bits.valid(i)   := i.U < deqCount // 指令数量不足的槽位设置为无效
  }

  // ---- 入队逻辑：计算每个槽位的累计偏移，用于确定写入缓冲区的位置 ----
  val offsets = Wire(Vec(4, UInt(idxWidth.W)))
  offsets(0) := 0.U
  for (i <- 1 until 4) {
    offsets(i) := offsets(i - 1) +& Mux(enq.bits.valid(i - 1), 1.U, 0.U)
  }

  // ---- 入队/出队的实际使能（flush 时不执行）----
  val actualEnq = enq.fire && !flush
  val actualDeq = deq.fire && !flush

  when(flush) {
    // flush 时重置所有状态
    head := 0.U
    tail := 0.U
  }.otherwise {
    when(actualEnq) { // 入队：将有效指令（含预测信息）写入缓冲区
      for (i <- 0 until 4) {
        when(enq.bits.valid(i)) {
          val writeIdx = (idx(tail) +% offsets(i))
          buffer(writeIdx).inst           := enq.bits.entries(i).inst
          buffer(writeIdx).pc             := enq.bits.entries(i).pc
          buffer(writeIdx).predict_taken  := enq.bits.entries(i).predict_taken
          buffer(writeIdx).predict_target := enq.bits.entries(i).predict_target
          buffer(writeIdx).bht_meta       := enq.bits.entries(i).bht_meta
        }
      }
      tail := tail + enqCount
    }
    when(actualDeq) { // 出队：移动头指针，出队数量 = deqCount
      head := head + deqCount
    }
  }

  // ---- 出入队信号：空闲槽位足够时才接受入队；缓冲区非空时可出队 ----
  enq.ready := (depth.U - count) >= enqCount
  deq.valid := count > 0.U
}
