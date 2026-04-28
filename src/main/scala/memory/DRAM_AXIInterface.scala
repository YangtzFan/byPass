package mycpu.memory

import chisel3._
import chisel3.util._

// ============================================================================
// DRAM_AXIInterface —— DRAM 的 AXI 从设备适配器（TD-A：outstanding=2 升级版）
// ============================================================================
// 本模块负责把 AXI 主设备（AXIStoreQueue）发起的事务，转换为对 32 位字宽 DRAM
// 的单端口操作。物理硬约束：DRAM 每周期最多执行 1 次读或 1 次写。
//
// TD-A 升级要点（参见 REF.md §3 / §6 版本 A）：
//   1. 读路径不再「AR 直接绑定 R」，而是
//        AR  → readReqFifo（深 2，存读地址 + axi id）
//        DRAM scheduler → 每拍最多发 1 个 read 给 DRAM
//        DRAM 读结果 → rRespFifo（深 2，存数据 + axi id）
//        rRespFifo → axi.r 通道
//      `axi.ar.ready := readReqFifo.io.enq.ready`，与 DRAM 是否空闲解耦；
//   2. 写路径仍然单事务（AW + W 同拍握手），保持旧行为：
//        - 上游 AXIStoreQueue 在有读 outstanding 时不会发起写（MVP 简化策略）；
//        - 写不进入 FIFO，命中时直接走 DRAM 写口；
//   3. 仲裁优先级：
//        - 当 axi.aw.valid && axi.w.valid（写事务到达）：写优先抢占 DRAM 一拍；
//        - 否则 readReqFifo 非空 → 发出读；
//        - 既无写也无读 → DRAM idle。
//      由于上游约定「有读 outstanding 时不发写」，本仲裁器主要是防御性兜底。
//
// 关键不变量：
//   - 同一拍 DRAM 端口最多被 1 个事件占用（读或写择一）；
//   - 读响应严格按 readReqFifo 入队顺序返回（顺序 outstanding，不需要 ID 路由）；
//   - axi.ar.ready 仅取决于 readReqFifo.enq.ready，与 DRAM 当拍是否在写无关；
//   - 写事务保持 AW+W 同拍 + 单 outstanding（B 通道挂起到主设备拿走 B 为止）。
// ============================================================================
class DRAM_AXIInterface extends Module {
  val axi = IO(Flipped(new AXIBundle()))

  // ---- 内部 DRAM 实例 ----
  val memDram  = Module(new DRAM)
  val wordAddr = Wire(UInt(16.W)) // DRAM 字寻址（16 位 = 64K 字）
  wordAddr := 0.U
  memDram.io.a := wordAddr
  memDram.io.we := false.B
  memDram.io.d  := 0.U

  // ============================================================================
  // 读路径：AR FIFO + 读响应 FIFO
  // ============================================================================
  // ReadReq：存 AR 上下文，DRAM scheduler 出队后驱动 DRAM 读端口；
  // ReadResp：存 DRAM 读出的数据 + axi id，弹出后驱动 axi.r 通道返回主设备。
  // ----------------------------------------------------------------------------
  class ReadReq extends Bundle {
    val addr = UInt(axi.p.addrBits.W)
    val id   = UInt(axi.p.idBits.W)
  }
  class ReadResp extends Bundle {
    val data = UInt(axi.p.dataBits.W)
    val id   = UInt(axi.p.idBits.W)
  }

  // 深度 2：上游 outstanding=2 的最大同时挂起数。
  val readReqFifo  = Module(new Queue(new ReadReq, entries = 2, pipe = false, flow = false))
  val readRespFifo = Module(new Queue(new ReadResp, entries = 2, pipe = false, flow = false))

  // AR 入 FIFO：默认全部反压关；只在 enq 通道驱动。
  readReqFifo.io.enq.valid     := axi.ar.valid
  readReqFifo.io.enq.bits.addr := axi.ar.bits.addr
  readReqFifo.io.enq.bits.id   := axi.ar.bits.id
  // 关键：ar.ready 与 DRAM 是否空闲解耦，仅看 FIFO 是否能收。
  axi.ar.ready := readReqFifo.io.enq.ready

  // ============================================================================
  // 写路径：直通 DRAM 写口，单 outstanding
  // ============================================================================
  // 状态机仅追踪 B 通道是否还有未交付的写响应（sIdle / sWriteResp）。
  // AW+W 必须同拍达成握手，写命中时占用 DRAM 一拍。
  // ----------------------------------------------------------------------------
  val sWIdle :: sWriteResp :: Nil = Enum(2)
  val wState = RegInit(sWIdle)
  val savedBId = RegInit(0.U(axi.p.idBits.W))

  // 默认握手取消（在下面按需拉高）
  axi.aw.ready := false.B
  axi.w.ready  := false.B
  axi.b.valid  := false.B
  axi.b.bits.id   := savedBId
  axi.b.bits.resp := AXIResp.OKAY

  // 写事务命中条件：AW + W 同拍 valid，且 B 通道未挂起（wState=sIdle）。
  val writeFire = (wState === sWIdle) && axi.aw.valid && axi.w.valid

  // 读出队条件：FIFO 非空 + DRAM 当拍没被写占用 + 响应 FIFO 有空位。
  val readSchedFire = readReqFifo.io.deq.valid && !writeFire && readRespFifo.io.enq.ready

  // ----------------------------------------------------------------------------
  // DRAM 端口仲裁：写优先 → 读 → idle
  // ----------------------------------------------------------------------------
  when(writeFire) {
    // 写：AW+W 同拍握手，按 wstrb 合并旧字并写回。
    axi.aw.ready := true.B
    axi.w.ready  := true.B
    val byteAddr = axi.aw.bits.addr
    val wAddr    = byteAddr(17, 2)
    wordAddr     := wAddr
    val oldWord = memDram.io.spo
    val mergedBytes = Wire(Vec(4, UInt(8.W)))
    for (b <- 0 until 4) {
      mergedBytes(b) := Mux(
        axi.w.bits.strb(b),
        axi.w.bits.data(b * 8 + 7, b * 8),
        oldWord(b * 8 + 7, b * 8)
      )
    }
    memDram.io.we := true.B
    memDram.io.d  := Cat(mergedBytes(3), mergedBytes(2), mergedBytes(1), mergedBytes(0))
    savedBId := axi.aw.bits.id
    wState   := sWriteResp
  }.elsewhen(readSchedFire) {
    // 读：从 readReqFifo 出队一笔，驱动 DRAM 组合读取，结果当拍压入 readRespFifo。
    val byteAddr = readReqFifo.io.deq.bits.addr
    wordAddr := byteAddr(17, 2)
  }

  // readReqFifo 出队 / readRespFifo 入队 严格同拍：DRAM 当拍组合输出读数据。
  readReqFifo.io.deq.ready := readSchedFire
  readRespFifo.io.enq.valid := readSchedFire
  readRespFifo.io.enq.bits.data := memDram.io.spo
  readRespFifo.io.enq.bits.id   := readReqFifo.io.deq.bits.id

  // ----------------------------------------------------------------------------
  // 写响应（B 通道）：state 机 sWriteResp 状态下挂起 B，直到主设备收走。
  // ----------------------------------------------------------------------------
  when(wState === sWriteResp) {
    axi.b.valid := true.B
    when(axi.b.ready) { wState := sWIdle }
  }

  // ----------------------------------------------------------------------------
  // 读响应（R 通道）：从 readRespFifo 出队后直接送 axi.r。
  // ----------------------------------------------------------------------------
  axi.r.valid     := readRespFifo.io.deq.valid
  axi.r.bits.data := readRespFifo.io.deq.bits.data
  axi.r.bits.id   := readRespFifo.io.deq.bits.id
  axi.r.bits.resp := AXIResp.OKAY
  readRespFifo.io.deq.ready := axi.r.ready
}
