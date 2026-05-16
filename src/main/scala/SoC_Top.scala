package mycpu

import chisel3._
import chisel3.util._
import mycpu.memory._
import mycpu.cache._
import mycpu.dataLane._

// ============================================================================
// SoC_Top —— 片上系统顶层
// ============================================================================
// 由 CPUConfig 中的 `dcacheSize` / `icacheSize` 联合控制访存路径形态：
//
// 数据通路（D-Cache）：
//   - dcacheSize > 0：myCPU.sq* ↔ AXIStoreQueue ↔ [AW+W/AR/B/R 5 通道 Queue]
//                    ↔ AXIToDCacheBridge ↔ DCache ↔ [AW+W/AR/B/R 5 通道 Queue]
//                    ↔ DRAM_AXIInterface
//   - dcacheSize == 0：myCPU.sq* ↔ AXIStoreQueue ↔ [AW+W/AR/B/R 5 通道 Queue]
//                    ↔ DRAM_AXIInterface
//
// 指令通路（I-Cache）：
//   - icacheSize > 0：myCPU.fetchMem ↔ [14b/128b Queue 对]
//                    ↔ ICache ↔ [14b/128b Queue 对] ↔ IROM
//   - icacheSize == 0：myCPU.fetchMem ↔ [14b/128b Queue 对] ↔ IROM
//
// AXI 5 通道 Queue 直接 inline 在本文件内（私有 def axiQueueChain）：
//   - AW + W 必须同拍 valid（DRAM_AXIInterface 不变量），合并为单个 WriteTxn Queue；
//   - AR / B / R 三个通道各自独立 Queue。
//
// difftest 约束：实例命名必须是 `axiStoreQueue`（lua harness 读取 `u_soc.axiStoreQueue.count`）。
// ============================================================================
class SoC_Top extends Module {
  val commitWidth = CPUConfig.commitWidth

  val io = IO(new Bundle {
    val debug_commit_count = Output(UInt(log2Ceil(commitWidth + 1).W))
    val debug_commit = Output(Vec(commitWidth, new Bundle {
      val pc        = UInt(32.W)
      val reg_wen   = Bool()
      val reg_waddr = UInt(5.W)
      val reg_wdata = UInt(32.W)

      val ram_wen   = Bool()
      val ram_waddr = UInt(32.W)
      val ram_wdata = UInt(32.W)
      val ram_wmask = UInt(32.W)
    }))
  })

  val coreCpu = Module(new MyCPU)

  // ============================================================================
  // 延时建模：所有 cache / 主存路径统一使用 `mycpu.memory.LatencyPipe` 模块。
  // ----------------------------------------------------------------------------
  //   - I-Cache 取指 / 关 Cache 旁路（14b/128b Decoupled）：直接 Module(new LatencyPipe)
  //   - D-Cache↔DRAM（AXIBundle 5 通道）：LatencyPipe.axiChain（内部把 AW+W
  //     合并 WriteTxn pipe，AR / R / B 三个通道各自独立 LatencyPipe）
  // 不再有任何 inline 的 axiQueueChain / latencyChain 私有方法。
  // ============================================================================
  val uDramAxiIf = Module(new DRAM_AXIInterface)

  // ============================================================================
  // 数据路径（D-Cache 开 / 关 两种形态）
  // ============================================================================
  val axiMasterDebug = Wire(new AXISQDebugIO)

  if (CPUConfig.dcacheSize > 0) {
    // -------------------- 开启 D-Cache --------------------
    val axiStoreQueue = Module(new AXIStoreQueue)
    val cacheBridge   = Module(new AXIToDCacheBridge)
    val dcache        = Module(new DCache)

    // CPU 前端 ↔ AXIStoreQueue
    coreCpu.sqEnq      <> axiStoreQueue.enq
    coreCpu.sqQuery    <> axiStoreQueue.query
    coreCpu.sqLoadAddr <> axiStoreQueue.loadAddr
    coreCpu.sqLoadData <> axiStoreQueue.loadData

    // AXIStoreQueue ↔ Queue 延时链 ↔ AXIToDCacheBridge
    val axiSqToBridge = Wire(new AXIBundle())
    LatencyPipe.axiChain(axiStoreQueue.axi, axiSqToBridge, CPUConfig.qDepthCpuToCache)
    cacheBridge.up    <> axiSqToBridge

    // bridge → DCache CPU 端口
    dcache.enq      <> cacheBridge.dcEnq
    dcache.loadAddr <> cacheBridge.dcLoadAddr
    dcache.loadData <> cacheBridge.dcLoadData

    // DCache 的 query 端口未使用，拉零
    for (i <- 0 until dcache.query.length) {
      dcache.query(i).valid    := false.B
      dcache.query(i).wordAddr := 0.U
      dcache.query(i).loadMask := 0.U
    }

    // DCache ↔ LatencyPipe 5-通道 AXI 延时链 ↔ DRAM
    LatencyPipe.axiChain(dcache.axi, uDramAxiIf.axi, CPUConfig.qDepthCacheToMem)

    axiMasterDebug <> axiStoreQueue.debug
  } else {
    // -------------------- 关闭 D-Cache（对照组）--------------------
    val axiStoreQueue = Module(new AXIStoreQueue)

    coreCpu.sqEnq      <> axiStoreQueue.enq
    coreCpu.sqQuery    <> axiStoreQueue.query
    coreCpu.sqLoadAddr <> axiStoreQueue.loadAddr
    coreCpu.sqLoadData <> axiStoreQueue.loadData

    // AXIStoreQueue ↔ LatencyPipe 5-通道 AXI 延时链 ↔ DRAM
    LatencyPipe.axiChain(axiStoreQueue.axi, uDramAxiIf.axi, CPUConfig.qDepthCpuToMem)

    axiMasterDebug <> axiStoreQueue.debug
  }

  // ============================================================================
  // 指令路径（I-Cache 开 / 关 两种形态）
  // ----------------------------------------------------------------------------
  // 通用：CPU 侧暴露的 fetchMem 端口与下方两种形态对接。
  //   - fetchMem.reqAddr  : Decoupled(14.W) - CPU → 取指系统
  //   - fetchMem.respData : Decoupled(128.W) - 取指系统 → CPU
  // ============================================================================
  val irom = Module(new IROM)

  // 与 IROM 组合读对接的辅助函数：
  //   把一个 14-bit Decoupled 请求 + 128-bit Decoupled 响应"贴合"到 IROM。
  //   IROM 是组合读，可以在请求 fire 的同一拍把数据放到响应；
  //   通过 reqIn.fire 直接驱动 respOut.valid，并使 reqIn.ready := respOut.ready。
  // 但更稳健的写法：respOut.valid := reqIn.valid，reqIn.ready := respOut.ready，
  //   IROM addr 由 reqIn.bits 直接驱动；同拍 fire 完成。
  def attachIromBackend(reqIn: DecoupledIO[UInt], respOut: DecoupledIO[UInt]): Unit = {
    irom.io.a       := reqIn.bits
    respOut.valid   := reqIn.valid
    respOut.bits    := irom.io.spo
    reqIn.ready     := respOut.ready
  }

  if (CPUConfig.icacheSize > 0) {
    // -------------------- 开启 I-Cache --------------------
    val icache = Module(new ICache)

    // Fetch ↔ LatencyPipe 对（命中路径，硬延迟 qDepthCpuToCache 拍）↔ ICache
    val fetchReqPipe  = Module(new LatencyPipe(UInt(14.W),  CPUConfig.qDepthCpuToCache))
    val fetchRespPipe = Module(new LatencyPipe(UInt(128.W), CPUConfig.qDepthCpuToCache))
    fetchReqPipe.io.enq  <> coreCpu.fetchMem.reqAddr
    icache.loadAddr      <> fetchReqPipe.io.deq
    fetchRespPipe.io.enq <> icache.loadData
    coreCpu.fetchMem.respData <> fetchRespPipe.io.deq

    // ICache ↔ LatencyPipe 对（miss refill 路径，硬延迟 qDepthCacheToMem 拍）↔ IROM
    val memReqPipe  = Module(new LatencyPipe(UInt(14.W),  CPUConfig.qDepthCacheToMem))
    val memRespPipe = Module(new LatencyPipe(UInt(128.W), CPUConfig.qDepthCacheToMem))
    memReqPipe.io.enq <> icache.memAddr
    memRespPipe.io.enq.valid := false.B // 默认拉零，下面 attachIromBackend 会接管
    memRespPipe.io.enq.bits  := 0.U
    attachIromBackend(memReqPipe.io.deq, memRespPipe.io.enq)
    icache.memData <> memRespPipe.io.deq
  } else {
    // -------------------- 关闭 I-Cache（旁路）--------------------
    // Fetch ↔ LatencyPipe 对（硬延迟 qDepthCpuToMem 拍）↔ IROM
    val fetchReqPipe  = Module(new LatencyPipe(UInt(14.W),  CPUConfig.qDepthCpuToMem))
    val fetchRespPipe = Module(new LatencyPipe(UInt(128.W), CPUConfig.qDepthCpuToMem))
    fetchReqPipe.io.enq <> coreCpu.fetchMem.reqAddr
    fetchRespPipe.io.enq.valid := false.B
    fetchRespPipe.io.enq.bits  := 0.U
    attachIromBackend(fetchReqPipe.io.deq, fetchRespPipe.io.enq)
    coreCpu.fetchMem.respData <> fetchRespPipe.io.deq
  }

  // ------------------------------------------------------------------
  // 转发多条提交观测信号（Phase A.2：按 ROB lane 的 store_ordinal 把 axiMasterDebug Vec 映射回 commit lane）
  // ------------------------------------------------------------------
  io.debug_commit_count := coreCpu.io.commit_count

  val K = mycpu.dataLane.LaneCapability.storeLanes.size
  for (i <- 0 until commitWidth) {
    val cmt = coreCpu.io.commit(i)
    val laneActive = coreCpu.io.commit_count > i.U
    io.debug_commit(i).pc        := Mux(laneActive, cmt.pc, 0.U)
    io.debug_commit(i).reg_wen   := cmt.reg_wen
    io.debug_commit(i).reg_waddr := Mux(cmt.reg_wen, cmt.reg_waddr, 0.U)
    io.debug_commit(i).reg_wdata := Mux(cmt.reg_wen, cmt.reg_wdata, 0.U)
    // 选出该 lane 对应的 axiMasterDebug Vec 项；非 store lane 输出 0。
    val ord = cmt.store_ordinal
    val safeOrd = Mux(ord < K.U, ord, 0.U)
    val laneDebug = axiMasterDebug.lane(safeOrd)
    val isStoreCommit = cmt.is_store && laneActive
    io.debug_commit(i).ram_wen   := isStoreCommit && laneDebug.commitRamWen
    io.debug_commit(i).ram_waddr := Mux(isStoreCommit && laneDebug.commitRamWen, laneDebug.commitRamWaddr, 0.U)
    io.debug_commit(i).ram_wdata := Mux(isStoreCommit && laneDebug.commitRamWen, laneDebug.commitRamWdata, 0.U)
    io.debug_commit(i).ram_wmask := Mux(isStoreCommit && laneDebug.commitRamWen, laneDebug.commitRamWmask, 0.U)
  }
}

