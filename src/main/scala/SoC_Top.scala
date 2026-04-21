package mycpu

import chisel3._
import chisel3.util._
import mycpu.memory._
import mycpu.dataLane._

// ============================================================================
// SoC_Top —— 片上系统顶层模块
// ============================================================================
// 将 CPU 核心、IROM、DRAM 驱动连接在一起
// 新增 MemReq/MemResp 1 周期适配器：
//   CPU 发出 DecoupledIO MemReq → 适配器接受 → 1 周期后返回 MemResp
//   写请求：适配器在接受后的下一周期驱动 DRAM we，writeExecuted 防止重复写
//   读请求：DRAM 读是组合逻辑，适配器在响应周期直接返回 rdata
// ============================================================================
class SoC_Top extends Module {
  val io = IO(new Bundle {
    val debug_commit_have_inst = Output(Bool())
    val debug_commit_pc = Output(UInt(32.W))
    val debug_commit_reg_wen = Output(Bool())
    val debug_commit_reg_waddr = Output(UInt(5.W))
    val debug_commit_reg_wdata = Output(UInt(32.W))

    val debug_commit_ram_wen = Output(Bool())
    val debug_commit_ram_waddr = Output(UInt(32.W))
    val debug_commit_ram_wdata = Output(UInt(32.W))
    val debug_commit_ram_wmask = Output(UInt(32.W))
  })

  // CPU 核心实例
  val coreCpu = Module(new myCPU)

  // ---- IROM ----
  val irom = Module(new IROM)
  irom.io.a := coreCpu.inst_addr_o
  coreCpu.inst_i := irom.io.spo

  // ---- DRAM 驱动器 ----
  val uDramDriver = Module(new dram_driver)

  // ========================================================================
  // MemReq/MemResp 1 周期适配器
  // ========================================================================
  // 状态机：sAccept（接受请求）→ sRespond（返回响应）
  val sAccept :: sRespond :: Nil = Enum(2)
  val adapterState = RegInit(sAccept)

  // 保存请求信息
  val reqIsWrite = RegInit(false.B)
  val reqAddr    = RegInit(0.U(32.W))
  val reqWdata   = RegInit(0.U(32.W))
  val reqWstrb   = RegInit(0.U(4.W))
  val writeExecuted = RegInit(false.B) // 防止重复写 DRAM

  // 默认：不接受请求，不返回响应
  coreCpu.memReq.ready  := false.B
  coreCpu.memResp.valid := false.B
  coreCpu.memResp.bits  := 0.U.asTypeOf(new MemRespBundle)

  // DRAM 驱动默认值
  uDramDriver.io.addr  := 0.U
  uDramDriver.io.wdata := 0.U
  uDramDriver.io.wstrb := 0.U
  uDramDriver.io.we    := false.B

  switch(adapterState) {
    is(sAccept) {
      // 可以接受新请求
      coreCpu.memReq.ready := true.B
      when(coreCpu.memReq.fire) {
        reqIsWrite := coreCpu.memReq.bits.isWrite
        reqAddr    := coreCpu.memReq.bits.addr
        reqWdata   := coreCpu.memReq.bits.wdata
        reqWstrb   := coreCpu.memReq.bits.wstrb
        writeExecuted := false.B
        adapterState := sRespond
      }
    }

    is(sRespond) {
      // 写请求：驱动 DRAM 写端口（仅一次）
      when(reqIsWrite && !writeExecuted) {
        uDramDriver.io.addr  := reqAddr(17, 0)
        uDramDriver.io.wdata := reqWdata
        uDramDriver.io.wstrb := reqWstrb
        uDramDriver.io.we    := true.B
        writeExecuted := true.B
      }

      // 读请求：组合逻辑驱动 DRAM 读地址
      when(!reqIsWrite) {
        uDramDriver.io.addr := reqAddr(17, 0)
      }

      // 返回响应（sticky：保持 valid 直到 fire）
      coreCpu.memResp.valid     := true.B
      coreCpu.memResp.bits.rdata := uDramDriver.io.rdata

      when(coreCpu.memResp.fire) {
        adapterState := sAccept
      }
    }
  }

  // ---- Commit 阶段调试观测 ----
  io.debug_commit_have_inst := coreCpu.io.commit_valid
  io.debug_commit_pc        := coreCpu.io.commit_pc
  io.debug_commit_reg_wen   := coreCpu.io.commit_reg_wen
  io.debug_commit_reg_waddr := coreCpu.io.commit_reg_waddr
  io.debug_commit_reg_wdata := coreCpu.io.commit_reg_wdata
  io.debug_commit_ram_wen   := coreCpu.io.commit_ram_wen
  io.debug_commit_ram_waddr := coreCpu.io.commit_ram_waddr
  io.debug_commit_ram_wdata := coreCpu.io.commit_ram_wdata
  io.debug_commit_ram_wmask := coreCpu.io.commit_ram_wmask
}
