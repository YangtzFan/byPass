package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// FetchBuffer（取指缓冲区）—— 环形缓冲区
// ============================================================================
// 入队：每周期最多 4 条指令（来自 Fetch 的 FetchPacket）
// 出队：每周期 1 条指令（送给 Decode）
// 支持 flush：分支跳转或 Commit flush 时清空所有缓存的指令
//
// 核心作用：解耦 4-wide 取指与 1-wide 译码之间的带宽差异
// 当 Decode 来不及消耗，多余的指令先暂存在这里
// ============================================================================
class FetchBuffer(val depth: Int = CPUConfig.fetchBufferEntries) extends Module {
  private val idxWidth = log2Ceil(depth)

  val enq = IO(Flipped(Decoupled(new FetchPacket))) // 入队端口：4 条指令
  val deq = IO(Decoupled(new FetchBufferEntry))     // 出队端口：1 条指令
  val flush = IO(Input(Bool()))                     // 清空信号

  // 环形缓冲区存储
  val buffer = Reg(Vec(depth, new FetchBufferEntry))  // 数据存储

  // head/tail 指针比实际索引多 1 位，用最高位区分是否多绕了一圈
  private val ptrWidth = idxWidth + 1
  val head = RegInit(0.U(ptrWidth.W)) // 出队指针（最旧的指令）
  val tail = RegInit(0.U(ptrWidth.W)) // 入队指针（下一个写入位置）
  private def idx(ptr: UInt) = ptr(idxWidth - 1, 0) // 访问实际索引 = 指针低 idxW 位

  val count = tail - head // 从 head/tail 指针推导当前缓冲区中有效指令数量（无需额外寄存器）
  val enqCount = PopCount(enq.bits.validMask) // 本次 FetchPacket 中有多少条有效指令
  enq.ready := depth.U - count >= enqCount // 反压逻辑：空闲槽位足够时才接受入队

  // 出队有效性：缓冲区非空时即可出队
  // 注意：flush 不会组合逻辑地抑制 deq.valid，避免产生组合环路
  // 下游的流水线寄存器会负责損掉过时的指令
  deq.valid := count > 0.U
  deq.bits := buffer(idx(head))

  // 入队/出队的实际使能（flush 时不执行入队/出队）
  val actualEnq = enq.fire && !flush
  val actualDeq = deq.fire && !flush

  // 预计算每个槽位的累计偏移（用于确定写入缓冲区的位置）。offsets(i) = 求和 enq.bits.validMask 0 ~ i-1
  val offsets = Wire(Vec(4, UInt(idxWidth.W)))
  offsets(0) := 0.U
  for (i <- 1 until 4) {
    offsets(i) := offsets(i - 1) +& Mux(enq.bits.validMask(i - 1), 1.U, 0.U)
  }

  when(flush) { // flush 时重置所有状态
    head := 0.U
    tail := 0.U
  }.otherwise {
    when(actualEnq) { // 入队：将有效指令写入缓冲区
      for (i <- 0 until 4) {
        when(enq.bits.validMask(i)) {
          val writeIdx = (idx(tail) +% offsets(i)) // 写入位置 = tail低位 + 累计偏移
          buffer(writeIdx).inst := enq.bits.insts(i)
          buffer(writeIdx).pc := enq.bits.pcs(i)
        }
      }
      tail := tail + enqCount // 移动尾指针（含高位进位）
    }
    when(actualDeq) { // 出队：取出头部指令
      head := head + 1.U                   // 移动头指针（含高位进位）
    }
  }
}
