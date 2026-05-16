package mycpu.cache

import chisel3._
import chisel3.util._
import mycpu.memory.{AXIBundle, AXIResp, SQEnqPayload, AXISQQueryIO}
import mycpu.dataLane.LaneCapability

// ============================================================================
// AXIToDCacheBridge —— 将 AXI Slave 端口适配到 DCache 的 CPU 端接口
// ============================================================================
// 新架构下，DCache 不再直接对 CPU 暴露 enq/loadAddr/loadData 接口，
// 而是被插入到 AXIStoreQueue 与 DRAM 之间，作为"透明缓存"。
// 上游 AXIStoreQueue（已通过 AXIQueue 延迟）以 AXI Master 形式向下游
// 发起单字（4B）粒度的读 / 写事务；本桥接器把这些 AXI 事务转成
// 现有 DCache 的 CPU 端语义：
//   - AW + W（同拍 valid，由 AXIStoreQueue 保证）  ->  DCache.enq(SQEnqPayload)
//   - AR                                              ->  DCache.loadAddr
//   - R                                               <-  DCache.loadData
//
// 设计要点：
//   1) AXIStoreQueue 一旦发起 Store，会同时拉高 aw.valid 和 w.valid 并
//      等待 aw.ready && w.ready 同拍握手。因此本桥接器将
//      aw.ready 与 w.ready 都接到 enq.ready，仅当 aw.valid && w.valid
//      均成立时拉高，从而保证 AW/W 与 enq.fire 三者同拍。
//   2) 写响应 B 通道：enq.fire 后下一拍即可向上游回 B（OKAY）。
//      使用单拍寄存的 valid + id，保证 AXI 协议握手语义。
//   3) 读路径：AR 入队等价于 loadAddr.fire；为正确回 R.id，需要 FIFO
//      记录每个未完成 AR 的 id，与 DCache 内部 loadDataQ 顺序一致。
//      DCache 单 MSHR 保证 loadData 严格按 loadAddr 顺序返回，因此
//      简单 FIFO 即可对齐 R 与 AR。
//   4) DCache.query 在新架构下完全无人查询，由 SoC_Top 在外部统一拉零；
//      本模块不操心。
//   5) DCache.debug 是 commit 时刻的 RAM 观察口，新架构下 difftest 应改用
//      AXIStoreQueue.debug（CPU commit 时刻的真实观测），本桥接器不引出。
// ============================================================================
class AXIToDCacheBridge extends Module {

  // 上游 AXI Slave 端口（接 AXIQueue 的 down 端，再向上接 AXIStoreQueue.axi）
  val up = IO(Flipped(new AXIBundle()))

  // 下游 DCache CPU 端接口（本模块作为这些接口的驱动方）
  val dcEnq      = IO(Decoupled(new SQEnqPayload))
  val dcLoadAddr = IO(Decoupled(UInt(32.W)))
  val dcLoadData = IO(Flipped(Decoupled(UInt(32.W))))

  // ============================================================================
  // 写路径：AW + W -> enq；enq.fire 后产生 B 响应
  // ============================================================================
  // 单 outstanding write：等 B 被上游收走后才能接受下一个 AW+W。
  val sWIdle :: sWWaitB :: Nil = Enum(2)
  val wState = RegInit(sWIdle)
  val bIdReg = Reg(UInt(up.aw.bits.id.getWidth.W))

  // 在 sWIdle 才允许接收新的 AW/W；并且必须 AW、W、enq 都准备好
  val canAcceptWrite = (wState === sWIdle) && up.aw.valid && up.w.valid && dcEnq.ready

  up.aw.ready := canAcceptWrite
  up.w.ready  := canAcceptWrite

  dcEnq.valid          := canAcceptWrite
  dcEnq.bits.addr      := up.aw.bits.addr
  dcEnq.bits.wstrb     := up.w.bits.strb
  dcEnq.bits.wdata     := up.w.bits.data
  // mask/data 字段仅 difftest commit 路径使用，桥接路径上没有意义，但
  // DCache 内部会把 wstrb 转成 4 位 byte-mask 实际写 Cache 行，因此
  // 这里仅给 difftest 字段填零即可，不影响功能。
  dcEnq.bits.mask      := 0.U
  dcEnq.bits.data      := up.w.bits.data
  // storeSeq 用于 DCache 内部 spq forwarding 优先级比较（载入侧 overlay
  // 选最新匹配字节）。新架构下载入路径（AR->loadAddr）在 DCache 内部需
  // 依赖此字段，故由桥接器在每次 enq.fire 时单调自增一个本地计数器。
  // 位宽与 SQEnqPayload.storeSeq 一致；seqNewerOrEq 做循环比较，
  // 16 项 spq 不会在 2^(seqW-1) 窗口内发生混淆。
  val seqWLocal = dcEnq.bits.storeSeq.getWidth
  val seqCnt    = RegInit(0.U(seqWLocal.W))
  dcEnq.bits.storeSeq := seqCnt
  when(dcEnq.fire) {
    seqCnt := seqCnt + 1.U
  }

  when(canAcceptWrite) {
    bIdReg := up.aw.bits.id
    wState := sWWaitB
  }

  up.b.valid     := wState === sWWaitB
  up.b.bits.id   := bIdReg
  up.b.bits.resp := AXIResp.OKAY
  when(up.b.fire) {
    wState := sWIdle
  }

  // ============================================================================
  // 读路径：AR -> loadAddr；loadData -> R
  // ============================================================================
  // 用一个小 FIFO 记录飞行中 AR 的 id，对齐 R 通道返回顺序。
  // 深度选 4：DCache 单 MSHR + loadDataQ 深度 2，飞行 AR 最多 2 个，
  // 留一点余量。
  val rIdFifo = Module(new Queue(UInt(up.ar.bits.id.getWidth.W), 4))

  // 仅当 loadAddr 和 idFifo 都能接受时才接收 AR
  val canAcceptAR = up.ar.valid && dcLoadAddr.ready && rIdFifo.io.enq.ready
  up.ar.ready          := dcLoadAddr.ready && rIdFifo.io.enq.ready
  dcLoadAddr.valid     := up.ar.valid && rIdFifo.io.enq.ready
  dcLoadAddr.bits      := up.ar.bits.addr
  rIdFifo.io.enq.valid := canAcceptAR
  rIdFifo.io.enq.bits  := up.ar.bits.id

  // R 通道：loadData 返回时配上对应 id
  up.r.valid           := dcLoadData.valid && rIdFifo.io.deq.valid
  up.r.bits.id         := rIdFifo.io.deq.bits
  up.r.bits.data       := dcLoadData.bits
  up.r.bits.resp       := AXIResp.OKAY
  dcLoadData.ready     := up.r.ready && rIdFifo.io.deq.valid
  rIdFifo.io.deq.ready := up.r.fire
}
