package mycpu.dataLane

import chisel3._
import chisel3.util._

// ==========================================================================
// LSUArbiter —— Load/Store 外部访存仲裁器
// ==========================================================================
// 统一仲裁 Memory 阶段的 Load 读请求和 myCPU 的 Store drain 写请求
// 接入 SoC_Top 的 DecoupledIO MemReq/MemResp 接口
//
// 设计原则：
//   1. Store drain 优先于 Load（drain 完成才能释放 ROB head，减少阻塞）
//   2. 同一时刻只能有一个未完成的外部请求
//   3. 状态机保证请求-响应配对正确
//
// 状态机：
//   sIdle     → 空闲，可接受新请求
//   sLoadWait → 已发出 Load 读请求，等待响应
//   sStoreWait → 已发出 Store 写请求，等待响应
// ==========================================================================
class LSUArbiter extends Module {
  val io = IO(new Bundle {
    // ---- Memory 阶段的 Load 请求/响应 ----
    val loadReq  = Flipped(DecoupledIO(new MemReqBundle))   // Load 读请求（isWrite=false）
    val loadResp = DecoupledIO(new MemRespBundle)            // Load 读响应

    // ---- myCPU 的 Store drain 请求/响应 ----
    val drainReq  = Flipped(DecoupledIO(new MemReqBundle))  // Store drain 写请求（isWrite=true）
    val drainResp = DecoupledIO(new MemRespBundle)           // Store drain 写响应

    // ---- 外部 SoC_Top 的统一接口 ----
    val memReq  = DecoupledIO(new MemReqBundle)              // 统一外部请求
    val memResp = Flipped(DecoupledIO(new MemRespBundle))    // 统一外部响应
  })

  // 状态机定义
  val sIdle :: sLoadWait :: sStoreWait :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // 默认输出
  io.loadReq.ready  := false.B
  io.drainReq.ready := false.B
  io.loadResp.valid := false.B
  io.loadResp.bits  := 0.U.asTypeOf(new MemRespBundle)
  io.drainResp.valid := false.B
  io.drainResp.bits  := 0.U.asTypeOf(new MemRespBundle)
  io.memReq.valid   := false.B
  io.memReq.bits    := 0.U.asTypeOf(new MemReqBundle)
  io.memResp.ready  := false.B

  switch(state) {
    is(sIdle) {
      // Store drain 优先于 Load
      when(io.drainReq.valid) {
        // 转发 drain 请求到外部
        io.memReq.valid := true.B
        io.memReq.bits  := io.drainReq.bits
        io.drainReq.ready := io.memReq.ready
        when(io.memReq.fire) {
          state := sStoreWait
        }
      }.elsewhen(io.loadReq.valid) {
        // 转发 load 请求到外部
        io.memReq.valid := true.B
        io.memReq.bits  := io.loadReq.bits
        io.loadReq.ready := io.memReq.ready
        when(io.memReq.fire) {
          state := sLoadWait
        }
      }
    }

    is(sLoadWait) {
      // 等待 Load 响应，转发给 Memory 阶段
      io.memResp.ready  := io.loadResp.ready
      io.loadResp.valid := io.memResp.valid
      io.loadResp.bits  := io.memResp.bits
      when(io.loadResp.fire) {
        state := sIdle
      }
    }

    is(sStoreWait) {
      // 等待 Store drain 响应，转发给 myCPU
      io.memResp.ready   := io.drainResp.ready
      io.drainResp.valid := io.memResp.valid
      io.drainResp.bits  := io.memResp.bits
      when(io.drainResp.fire) {
        state := sIdle
      }
    }
  }
}
