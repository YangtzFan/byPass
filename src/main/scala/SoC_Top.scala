package mycpu

import chisel3._
import mycpu.memory._

// ============================================================================
// SoC_Top —— 片上系统顶层模块
// ============================================================================
// 将 CPU 核心、指令存储器（IROM）、数据存储器驱动（DRAM driver）连接在一起
// 并对外暴露 difftest 调试接口
// ============================================================================
class SoC_Top extends Module {
  val io = IO(new Bundle {
    // ---- difftest 调试观测端口（供仿真框架对比验证）----
    val debug_commit_have_inst = Output(Bool())    // 本周期是否有指令提交
    val debug_commit_pc = Output(UInt(32.W))       // 提交指令的 PC
    val debug_commit_reg_wen = Output(Bool())          // 是否写寄存器
    val debug_commit_reg_waddr = Output(UInt(5.W))     // 写入的寄存器编号
    val debug_commit_reg_wdata = Output(UInt(32.W))    // 写入的数据值

    val debug_commit_ram_wen = Output(Bool())    // 写入的数据值
    val debug_commit_ram_waddr = Output(UInt(32.W))    // 写入的数据值
    val debug_commit_ram_wdata = Output(UInt(32.W))    // 写入的数据值
    val debug_commit_ram_wmask = Output(UInt(32.W))    // 写入的数据值
  })

  // CPU 核心实例
  val coreCpu = Module(new myCPU)

  // ---- IROM（指令 ROM，只读）----
  // 128 位宽单读端口，每次返回 4 条指令
  val memIrom = Module(new IROM)
  memIrom.io.a := coreCpu.io.inst_addr_o // CPU 输出 14 位地址
  coreCpu.io.inst_i := memIrom.io.spo    // IROM 返回 128 位指令数据

  // ---- DRAM 驱动器（数据存储器读写控制）----
  // DRAM 的读写端口被 Load 和 Store 共享，通过 MUX 切换
  // Store 优先（Commit 阶段写入时，地址/掩码切换为 Store 的）
  val uDramDriver = Module(new dram_driver)

  // 地址和掩码：Store 优先于 Load
  uDramDriver.io.perip_addr := Mux(coreCpu.io.commit_ram_wen,
    coreCpu.io.commit_ram_waddr(17, 0), // Store 地址
    coreCpu.io.ram_addr_o(17, 0))       // Load 地址
  uDramDriver.io.perip_wdata := coreCpu.io.commit_ram_wdata // Store 写数据
  uDramDriver.io.perip_mask := Mux(coreCpu.io.commit_ram_wen,
    coreCpu.io.commit_ram_wmask, // Store 宽度掩码
    coreCpu.io.ram_mask_o)       // Load 宽度掩码
  uDramDriver.io.dram_wen := coreCpu.io.commit_ram_wen // 写使能
  coreCpu.io.ram_rdata_i := uDramDriver.io.perip_rdata // DRAM 读回数据

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
