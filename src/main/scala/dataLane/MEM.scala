package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// MEM（访存阶段）
// ============================================================================
// - Load 指令：在此阶段通过 DRAM 读端口读取数据
// - Store 指令：**不在此阶段写入** DRAM，只是携带地址/数据/掩码信息向后传递，
//   延迟到 Commit 阶段才实际写入（避免推测执行污染内存）
// - 其他指令：直接透传 Execute 阶段的结果
// ============================================================================
class MEM extends Module {
  val in = IO(Flipped(Decoupled(new Execute_MEM_Payload)))  // 输入：来自 EX
  val out = IO(Decoupled(new MEM_WB_Payload))           // 输出：送往 WB
  val io = IO(new Bundle {
    val ram_addr_o = Output(UInt(32.W))            // DRAM 读地址（Load 使用）
    val ram_mask_o = Output(UInt(3.W))             // 访存宽度掩码
    val ram_rdata_i = Input(UInt(32.W))            // DRAM 读回数据
    // Store 写入操作延迟到 Commit 阶段，此处不做
  })

  // 从类型编码中提取各指令类型
  val uType = in.bits.type_decode_together(8)
  val jal = in.bits.type_decode_together(7)
  val jalr = in.bits.type_decode_together(6)
  val lType = in.bits.type_decode_together(4)  // Load
  val iType = in.bits.type_decode_together(3)
  val sType = in.bits.type_decode_together(2)  // Store
  val rType = in.bits.type_decode_together(1)

  // DRAM 读端口：只有 Load 指令才发起读请求
  io.ram_addr_o := Mux(lType, in.bits.data, 0.U)         // Load 地址 = ALU 计算结果
  io.ram_mask_o := Mux(lType, in.bits.inst_funct3, 0.U)  // 访存宽度（LB/LH/LW）

  // ---- 输出结果打包 ----
  out.bits.pc := in.bits.pc
  out.bits.inst_rd := Mux(uType || jal || jalr || lType || iType || rType, in.bits.inst_rd, 0.U)
  out.bits.data := MuxCase(0.U, Seq(
    (uType || jal || jalr || iType || rType) -> in.bits.data, // 非 Load：透传 Execute 的结果
    lType -> io.ram_rdata_i                                    // Load：使用 DRAM 读回的数据
  ))
  out.bits.type_decode_together := in.bits.type_decode_together
  out.bits.robIdx := in.bits.robIdx
  out.bits.regWriteEnable := in.bits.regWriteEnable
  out.bits.isBranch := in.bits.isBranch
  out.bits.isJump := in.bits.isJump
  out.bits.predict_taken := in.bits.predict_taken
  out.bits.predict_target := in.bits.predict_target
  out.bits.actual_taken := in.bits.actual_taken
  out.bits.actual_target := in.bits.actual_target
  out.bits.mispredict := in.bits.mispredict
  out.bits.bht_meta := in.bits.bht_meta

  // Store 信息携带：将地址/数据/掩码传递到后续阶段，最终在 Commit 写入 DRAM
  out.bits.isStore := sType
  out.bits.store_addr := Mux(sType, in.bits.data, 0.U)        // Store 地址 = ALU 结果
  out.bits.store_data := Mux(sType, in.bits.reg_rdata2, 0.U)  // Store 数据 = rs2 的值
  out.bits.store_mask := Mux(sType, in.bits.inst_funct3, 0.U) // Store 宽度

  in.ready := out.ready
  out.valid := in.valid
}
