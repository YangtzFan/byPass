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

  val coreCpu = Module(new myCPU)
  val axiStoreQueue = Module(new AXIStoreQueue)

  // ---- IROM ----
  val irom = Module(new IROM)
  irom.io.a := coreCpu.inst_addr_o
  coreCpu.inst_i := irom.io.spo

  // ---- 内核前端接口 ↔ 核外 AXIStoreQueue ----
  coreCpu.sqEnq <> axiStoreQueue.enq
  coreCpu.sqQuery <> axiStoreQueue.query
  coreCpu.sqLoadReq <> axiStoreQueue.loadReq
  coreCpu.sqLoadResp <> axiStoreQueue.loadResp

  // ---- DRAM 驱动器 ----
  val uDramDriver = Module(new dram_driver)

  // ========================================================================
  // AXIStoreQueue 后端的一拍请求 / 响应适配
  // dram_driver 本身是“组合读 + 时钟写”，而 AXIStoreQueue 已经在内部保证
  // 任意时刻最多只有一笔后端请求在飞，所以这里不需要再维护额外状态机。
  // 只需完成两件事：
  //   1. 请求握手当拍，把地址/写数据直接送到 dram_driver；
  //   2. 下一拍给 AXIStoreQueue 返回一个 response 脉冲，维持它当前的 waitResp 时序假设。
  // ========================================================================
  val memReqFire = axiStoreQueue.memReq.valid
  val memRespValid = RegNext(memReqFire, false.B)
  val memRespRdata = RegEnable(uDramDriver.io.rdata, 0.U(32.W), memReqFire)

  axiStoreQueue.memReq.ready := true.B
  axiStoreQueue.memResp.valid := memRespValid
  axiStoreQueue.memResp.rdata := memRespRdata

  uDramDriver.io.addr := axiStoreQueue.memReq.addr(17, 0)
  uDramDriver.io.wdata := axiStoreQueue.memReq.wdata
  uDramDriver.io.wstrb := axiStoreQueue.memReq.wstrb
  uDramDriver.io.we := memReqFire && axiStoreQueue.memReq.isWrite

  // ------------------------------------------------------------------
  // 转发多条提交观测信号
  // ------------------------------------------------------------------
  // commit_count 由核内 ROB 提交端直接给出。
  // 当前实现中 axiStoreQueue.debug.commitRamWen 一拍内至多有 1 个 store 成功入队，
  // 与 commitWidth=1 场景完全对应；一旦扩宽到多提交，需在 AXIStoreQueue
  // debug 接口中同步提供 Vec 形式，否则多 lane 的 ram 观测会失准。
  io.debug_commit_count := coreCpu.io.commit_count

  // lane 0：寄存器写回信息来自 coreCpu.io.commit(0)，store 信息来自 axiStoreQueue.debug。
  io.debug_commit(0).pc        := Mux(coreCpu.io.commit_count =/= 0.U, coreCpu.io.commit(0).pc, 0.U)
  io.debug_commit(0).reg_wen   := coreCpu.io.commit(0).reg_wen
  io.debug_commit(0).reg_waddr := Mux(coreCpu.io.commit(0).reg_wen, coreCpu.io.commit(0).reg_waddr, 0.U)
  io.debug_commit(0).reg_wdata := Mux(coreCpu.io.commit(0).reg_wen, coreCpu.io.commit(0).reg_wdata, 0.U)

  io.debug_commit(0).ram_wen   := axiStoreQueue.debug.commitRamWen
  io.debug_commit(0).ram_waddr := Mux(axiStoreQueue.debug.commitRamWen, axiStoreQueue.debug.commitRamWaddr, 0.U)
  io.debug_commit(0).ram_wdata := Mux(axiStoreQueue.debug.commitRamWen, axiStoreQueue.debug.commitRamWdata, 0.U)
  io.debug_commit(0).ram_wmask := Mux(axiStoreQueue.debug.commitRamWen, axiStoreQueue.debug.commitRamWmask, 0.U)

  // 高位 lane 在 commitWidth>1 时使用。当前保持安全默认值。
  for (i <- 1 until commitWidth) {
    io.debug_commit(i).pc        := 0.U
    io.debug_commit(i).reg_wen   := false.B
    io.debug_commit(i).reg_waddr := 0.U
    io.debug_commit(i).reg_wdata := 0.U
    io.debug_commit(i).ram_wen   := false.B
    io.debug_commit(i).ram_waddr := 0.U
    io.debug_commit(i).ram_wdata := 0.U
    io.debug_commit(i).ram_wmask := 0.U
  }
}
