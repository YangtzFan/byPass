package mycpu.device

import chisel3._

class REG extends Module {
  val io = IO(new Bundle {
    val reg_wen_i = Input(Bool())
    val reg_waddr_i = Input(UInt(5.W))
    val reg_wdata_i = Input(UInt(32.W))
    val reg_raddr1_i = Input(UInt(5.W))
    val reg_raddr2_i = Input(UInt(5.W))
    val reg_rdata1_o = Output(UInt(32.W))
    val reg_rdata2_o = Output(UInt(32.W))
  })

  val regFile = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))

  when(io.reg_wen_i && io.reg_waddr_i =/= 0.U) {
    regFile(io.reg_waddr_i) := io.reg_wdata_i
  }

  // 写优先旁路：当同一周期 WB 写入和 ID 读取同一寄存器时，
  // 直接输出写入的新值，避免读到旧值（解决 3 级间距的数据冲突）。
  io.reg_rdata1_o := Mux(io.reg_raddr1_i === 0.U, 0.U,
    Mux(io.reg_wen_i && io.reg_waddr_i =/= 0.U && io.reg_waddr_i === io.reg_raddr1_i,
      io.reg_wdata_i, regFile(io.reg_raddr1_i)))
  io.reg_rdata2_o := Mux(io.reg_raddr2_i === 0.U, 0.U,
    Mux(io.reg_wen_i && io.reg_waddr_i =/= 0.U && io.reg_waddr_i === io.reg_raddr2_i,
      io.reg_wdata_i, regFile(io.reg_raddr2_i)))
}
