package mycpu

import chisel3._
import chisel3.util._
import mycpu.memory._
import mycpu.dataLane._

// ============================================================================
// SoC_Top —— 片上系统顶层
// ============================================================================
// 新架构中，AXIStoreQueue 被放在核外：
//   1. myCPU 仅暴露前端 enqueue / query / load req&resp 接口；
//   2. AXIStoreQueue 统一仲裁 committed store 写回和 load miss 外读；
//   3. DRAM 仍沿用当前的一拍请求 / 响应适配器；
//   4. difftest 现在允许在 store 成功写入 AXIStoreQueue 的同拍进行比对，
//      因此顶层不再需要额外缓存 commit / store-write 事件来重排观察时机。
// ============================================================================
class SoC_Top extends Module {
  // difftest 多条提交观测：
  //   1) io.debug_commit_count 标示本周期 commit 的指令条数（0..commitWidth）；
  //   2) 每个 lane 有独立的 pc / reg_wen / reg_waddr / reg_wdata / is_store；
  //   3) 每个 lane 有独立的 ram_wen / ram_waddr / ram_wdata / ram_wmask；
  // 当前 commitWidth=1，但接口已按 Vec 暴露，后续扩宽 retire 时只需改配置。
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
  val axiStoreQueue = Module(new AXIStoreQueue)

  // ---- IROM ----
  val irom = Module(new IROM)
  irom.io.a := coreCpu.inst_addr_o
  coreCpu.inst_i := irom.io.spo

  // ---- 内核前端接口 ↔ 核外 AXIStoreQueue ----
  coreCpu.sqEnq <> axiStoreQueue.enq
  coreCpu.sqQuery <> axiStoreQueue.query
  coreCpu.sqLoadAddr <> axiStoreQueue.loadAddr
  coreCpu.sqLoadData <> axiStoreQueue.loadData

  // ---- DRAM AXI Slave 接口适配器 ----
  // AXIStoreQueue 以 AXI 主设备身份对外发送 read/write 请求，
  // DRAM_AXIInterface 在内部将 AXI 时序桥接到原有的“组合读 + 时钟写”DRAM。
  val uDramAxiIf = Module(new DRAM_AXIInterface)
  axiStoreQueue.axi <> uDramAxiIf.axi

  // ------------------------------------------------------------------
  // 转发多条提交观测信号
  // ------------------------------------------------------------------
  // commit_count 由核内 ROB 提交端直接给出。
  // 当前实现中 axiStoreQueue.debug.commitRamWen 一拍内至多有 1 个 store 成功入队，
  // 与 commitWidth=1 场景完全对应；一旦扩宽到多提交，需在 AXIStoreQueue
  // debug 接口中同步提供 Vec 形式，否则多 lane 的 ram 观测会失准。
  io.debug_commit_count := coreCpu.io.commit_count

  // lane 0..commitWidth-1：寄存器写回信息来自 coreCpu.io.commit(i)。
  // store 信息仅 lane 0 来自 axiStoreQueue.debug（AXI 单事务 + commitMask 保证同拍
  // 至多 1 个 Store 提交，且位于 lane 0）。
  for (i <- 0 until commitWidth) {
    val cmt = coreCpu.io.commit(i)
    val laneActive = coreCpu.io.commit_count > i.U
    io.debug_commit(i).pc        := Mux(laneActive, cmt.pc, 0.U)
    io.debug_commit(i).reg_wen   := cmt.reg_wen
    io.debug_commit(i).reg_waddr := Mux(cmt.reg_wen, cmt.reg_waddr, 0.U)
    io.debug_commit(i).reg_wdata := Mux(cmt.reg_wen, cmt.reg_wdata, 0.U)
    if (i == 0) {
      io.debug_commit(i).ram_wen   := axiStoreQueue.debug.commitRamWen
      io.debug_commit(i).ram_waddr := Mux(axiStoreQueue.debug.commitRamWen, axiStoreQueue.debug.commitRamWaddr, 0.U)
      io.debug_commit(i).ram_wdata := Mux(axiStoreQueue.debug.commitRamWen, axiStoreQueue.debug.commitRamWdata, 0.U)
      io.debug_commit(i).ram_wmask := Mux(axiStoreQueue.debug.commitRamWen, axiStoreQueue.debug.commitRamWmask, 0.U)
    } else {
      io.debug_commit(i).ram_wen   := false.B
      io.debug_commit(i).ram_waddr := 0.U
      io.debug_commit(i).ram_wdata := 0.U
      io.debug_commit(i).ram_wmask := 0.U
    }
  }
}
