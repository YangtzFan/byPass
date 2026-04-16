package mycpu.device

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// PRF（Physical Register File）—— 物理寄存器堆
// ============================================================================
// 深度 128（p0~p127），数据宽度 32 位
// p0 硬编码为 0：写入 p0 的操作被忽略，读取 p0 总是返回 0
//
// 端口配置：
//   - 1 个写端口：由 Refresh 阶段驱动（执行结果写回物理寄存器）
//   - 2 个读端口：由 ReadReg 阶段驱动（按物理寄存器编号读取操作数）
//
// 写优先旁路：当同一周期写入和读取命中同一物理寄存器时，
// 直接输出写入的新值，避免读到旧值
// ============================================================================
class PRF extends Module {
  val io = IO(new Bundle {
    // ---- 写端口（Refresh 阶段写回执行结果）----
    val wen   = Input(Bool())                              // 写使能
    val waddr = Input(UInt(CPUConfig.prfAddrWidth.W))      // 写地址（物理寄存器编号）
    val wdata = Input(UInt(32.W))                          // 写数据（执行结果）

    // ---- 读端口 1（ReadReg 阶段读取 psrc1）----
    val raddr1 = Input(UInt(CPUConfig.prfAddrWidth.W))     // 读地址 1（物理源寄存器 1 编号）
    val rdata1 = Output(UInt(32.W))                        // 读数据 1

    // ---- 读端口 2（ReadReg 阶段读取 psrc2）----
    val raddr2 = Input(UInt(CPUConfig.prfAddrWidth.W))     // 读地址 2（物理源寄存器 2 编号）
    val rdata2 = Output(UInt(32.W))                        // 读数据 2
  })

  // 128 个 32 位物理寄存器，初始化为 0
  val regFile = RegInit(VecInit(Seq.fill(CPUConfig.prfEntries)(0.U(32.W))))

  // 写入逻辑：p0 不可写（p0 硬编码为 0）
  when(io.wen && io.waddr =/= 0.U) {
    regFile(io.waddr) := io.wdata
  }

  // 读端口 1（带写优先旁路）：p0 返回 0，同周期写读冲突时输出新值
  io.rdata1 := Mux(io.raddr1 === 0.U, 0.U,
    Mux(io.wen && io.waddr =/= 0.U && io.waddr === io.raddr1,
      io.wdata, regFile(io.raddr1)))

  // 读端口 2（带写优先旁路）：p0 返回 0，同周期写读冲突时输出新值
  io.rdata2 := Mux(io.raddr2 === 0.U, 0.U,
    Mux(io.wen && io.waddr =/= 0.U && io.waddr === io.raddr2,
      io.wdata, regFile(io.raddr2)))
}
