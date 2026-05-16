package mycpu.memory

import chisel3._
import chisel3.util._

// ============================================================================
// LatencyPipe —— N 拍硬延迟管道
// ----------------------------------------------------------------------------
// 用途：建模"主存延迟"或"Cache 命中延迟"。把 `io.enq` 输入流经过 **精确 N 拍**
// 流水线后输出到 `io.deq`。与 chisel3.util.Queue 区别：默认 Queue 在稀疏流量下
// 单 token 只走 1 拍，无法反映"队列深度=200"这样的延迟拍数语义；本模块通过
// N 级 (valid, bits) 寄存器组成的 shift pipeline 保证每个 enq.fire 的 token
// 必须经过 N 拍才会成为 deq.valid。
//
// 反压模型：末级 valid && !deq.ready 时整个 pipeline 停止移动（反压
// 沿 stageValid 链回到 enq.ready）；否则每拍同步右移：
//   stage(i+1) <= stage(i)，stage(0) <= io.enq
//
// 容量 = latency（最坏全满 N 个 token 在飞），稳态吞吐 = 1 token/cycle，
// 稀疏单 token 精确 N 拍延迟。
// ============================================================================
class LatencyPipe[T <: Data](gen: T, val latency: Int) extends Module {
  // latency == 0 时退化为组合直通（无寄存器），用于复现“无访存延迟”的理论上限场景；
  // latency >= 1 走原 N 拍 shift pipeline 实现。
  require(latency >= 0, s"LatencyPipe latency must be >= 0 (got $latency)")

  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen.cloneType)) // 上游入口：master 视角的 Decoupled
    val deq = Decoupled(gen.cloneType)          // 下游出口：slave 视角的 Decoupled
  })

  if (latency == 0) {
    // 组合直通：valid/bits/ready 三向直连，0 拍延迟、保持 Decoupled 握手语义。
    io.deq.valid := io.enq.valid
    io.deq.bits  := io.enq.bits
    io.enq.ready := io.deq.ready
  } else {

  // 每级一个 valid 位（复位为 false）+ 一个 bits 寄存器（无复位，仅在 valid=true 时有效）
  val stageValid = RegInit(VecInit(Seq.fill(latency)(false.B)))
  val stageBits  = Reg(Vec(latency, gen.cloneType))

  // 是否允许整体右移：末级 stage 当前为空，或末级被下游取走时
  val canShift = !stageValid(latency - 1) || io.deq.ready

  when (canShift) {
    // 从末级向前依次拷贝（latency-1 ← latency-2、... 、1 ← 0）
    for (i <- latency - 1 to 1 by -1) {
      stageValid(i) := stageValid(i - 1)
      stageBits(i)  := stageBits(i - 1)
    }
    // 第 0 级吸收新输入
    stageValid(0) := io.enq.valid
    stageBits(0)  := io.enq.bits
  }

  io.enq.ready := canShift                // 末级反压时整体冻结
  io.deq.valid := stageValid(latency - 1)
  io.deq.bits  := stageBits(latency - 1)
  } // end of latency >= 1 branch
}

// ============================================================================
// LatencyPipe 工具方法
// ----------------------------------------------------------------------------
// connect : 在一对 Decoupled 之间插入 N 拍硬延迟管道
// axiChain: 把 AXI 5 通道（AR / R / AW+W / B）按 latency 拍硬延迟串接，
//           同时保持 AW + W 同拍 valid 不变量（合并为单个 WriteTxn pipe）。
// ============================================================================
object LatencyPipe {
  /** 在两条 Decoupled 之间插入 N 拍硬延迟管道。 */
  def connect[T <: Data](src: DecoupledIO[T], dst: DecoupledIO[T], latency: Int): Unit = {
    val pipe = Module(new LatencyPipe(chiselTypeOf(src.bits), latency))
    pipe.io.enq.valid := src.valid
    pipe.io.enq.bits  := src.bits
    src.ready         := pipe.io.enq.ready
    dst.valid         := pipe.io.deq.valid
    dst.bits          := pipe.io.deq.bits
    pipe.io.deq.ready := dst.ready
  }

  /** 把上游 AXI（master 视角）经过 latency 拍硬延迟链接到下游 AXI（slave 视角）。
    *
    * 与原 SoC_Top.axiQueueChain 行为对齐：
    *   1) 写事务：AW + W 同拍 valid 不变量 → 合并为单个 LatencyPipe[WriteTxn]
    *      a. 入队 valid := up.aw.valid && up.w.valid（成对入队，避免半事务）
    *      b. up.aw.ready / up.w.ready 双向依赖对端 valid 与 pipe enq.ready
    *      c. 出队同拍驱动 down.aw + down.w，两端 ready 均拉高才出队
    *   2) AR / B / R 各自独立 LatencyPipe（不依赖跨通道同拍语义）
    */
  def axiChain(up: AXIBundle, down: AXIBundle, latency: Int): Unit = {
    require(latency >= 0, s"LatencyPipe.axiChain latency must be >= 0 (got $latency)")

    if (latency == 0) {
      // 0 拍：5 通道按 AXI 方向直接组合直通（等价于 `up <> down`，不引入 AW+W pairing）。
      down.aw.valid := up.aw.valid
      down.aw.bits  := up.aw.bits
      up.aw.ready   := down.aw.ready
      down.w.valid  := up.w.valid
      down.w.bits   := up.w.bits
      up.w.ready    := down.w.ready
      down.ar.valid := up.ar.valid
      down.ar.bits  := up.ar.bits
      up.ar.ready   := down.ar.ready
      up.r.valid    := down.r.valid
      up.r.bits     := down.r.bits
      down.r.ready  := up.r.ready
      up.b.valid    := down.b.valid
      up.b.bits     := down.b.bits
      down.b.ready  := up.b.ready
      return
    }

    // ---- 写事务合并：AW + W 必须同拍 valid，按一对 WriteTxn 入队 ----
    class WriteTxn extends Bundle {
      val awId   = UInt(up.p.idBits.W)
      val awAddr = UInt(up.p.addrBits.W)
      val wData  = UInt(up.p.dataBits.W)
      val wStrb  = UInt(up.p.strbBits.W)
    }
    val writePipe = Module(new LatencyPipe(new WriteTxn, latency))
    val pairValid = up.aw.valid && up.w.valid
    writePipe.io.enq.valid       := pairValid
    writePipe.io.enq.bits.awId   := up.aw.bits.id
    writePipe.io.enq.bits.awAddr := up.aw.bits.addr
    writePipe.io.enq.bits.wData  := up.w.bits.data
    writePipe.io.enq.bits.wStrb  := up.w.bits.strb
    up.aw.ready := writePipe.io.enq.ready && up.w.valid
    up.w.ready  := writePipe.io.enq.ready && up.aw.valid

    down.aw.valid     := writePipe.io.deq.valid
    down.aw.bits.id   := writePipe.io.deq.bits.awId
    down.aw.bits.addr := writePipe.io.deq.bits.awAddr
    down.w.valid      := writePipe.io.deq.valid
    down.w.bits.data  := writePipe.io.deq.bits.wData
    down.w.bits.strb  := writePipe.io.deq.bits.wStrb
    writePipe.io.deq.ready := down.aw.ready && down.w.ready

    // ---- AR 通道（主→从） ----
    val arPipe = Module(new LatencyPipe(new AXIAddr(up.p), latency))
    arPipe.io.enq.valid := up.ar.valid
    arPipe.io.enq.bits  := up.ar.bits
    up.ar.ready         := arPipe.io.enq.ready
    down.ar.valid       := arPipe.io.deq.valid
    down.ar.bits        := arPipe.io.deq.bits
    arPipe.io.deq.ready := down.ar.ready

    // ---- R 通道（从→主） ----
    val rPipe = Module(new LatencyPipe(new AXIR(up.p), latency))
    rPipe.io.enq.valid := down.r.valid
    rPipe.io.enq.bits  := down.r.bits
    down.r.ready       := rPipe.io.enq.ready
    up.r.valid         := rPipe.io.deq.valid
    up.r.bits          := rPipe.io.deq.bits
    rPipe.io.deq.ready := up.r.ready

    // ---- B 通道（从→主） ----
    val bPipe = Module(new LatencyPipe(new AXIB(up.p), latency))
    bPipe.io.enq.valid := down.b.valid
    bPipe.io.enq.bits  := down.b.bits
    down.b.ready       := bPipe.io.enq.ready
    up.b.valid         := bPipe.io.deq.valid
    up.b.bits          := bPipe.io.deq.bits
    bPipe.io.deq.ready := up.b.ready
  }
}
