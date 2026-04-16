package mycpu.device

import chisel3._

// ============================================================================
// REG —— 32 x 32-bit 通用寄存器堆
// ============================================================================
// 端口配置：
//   - 1 个写端口：由 Commit 阶段驱动（ROB 提交时写回）
//   - 2 个读端口：
//     - 端口 1/2：Dispatch 阶段读取 rs1/rs2（作为转发链的兜底值）
//
// x0 硬编码为 0：写入 x0 的操作被忽略，读取 x0 总是返回 0
//
// 写优先旁路：当同一周期的 Commit 写入和读取命中同一寄存器时，
// 直接输出写入的新值，避免读到旧值
// ============================================================================
class REG extends Module {
  val io = IO(new Bundle {
    val reg_wen_i    = Input(Bool())         // 写使能（Commit 阶段）
    val reg_waddr_i  = Input(UInt(5.W))      // 写地址（目标寄存器编号）
    val reg_wdata_i  = Input(UInt(32.W))     // 写数据（执行结果）
    val reg_raddr1_i = Input(UInt(5.W))      // Dispatch 读端口 1 地址（rs1）
    val reg_raddr2_i = Input(UInt(5.W))      // Dispatch 读端口 2 地址（rs2）
    val reg_rdata1_o = Output(UInt(32.W))    // Dispatch 读端口 1 数据
    val reg_rdata2_o = Output(UInt(32.W))    // Dispatch 读端口 2 数据
  })

  // 32 个 32 位寄存器，初始化为 0
  val regFile = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))

  // 写入逻辑：x0 不可写
  when(io.reg_wen_i && io.reg_waddr_i =/= 0.U) {
    regFile(io.reg_waddr_i) := io.reg_wdata_i
  }

  // ---- Dispatch 阶段读端口（带写优先旁路）----
  // 写优先旁路：当同一周期 Commit 写入和 Dispatch 读取命中同一寄存器时，
  // 直接输出写入的新值，避免读到旧值
  io.reg_rdata1_o := Mux(io.reg_raddr1_i === 0.U, 0.U,
    Mux(io.reg_wen_i && io.reg_waddr_i =/= 0.U && io.reg_waddr_i === io.reg_raddr1_i,
      io.reg_wdata_i, regFile(io.reg_raddr1_i)))
  io.reg_rdata2_o := Mux(io.reg_raddr2_i === 0.U, 0.U,
    Mux(io.reg_wen_i && io.reg_waddr_i =/= 0.U && io.reg_waddr_i === io.reg_raddr2_i,
      io.reg_wdata_i, regFile(io.reg_raddr2_i)))

}
