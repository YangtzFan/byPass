package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// MEM（访存阶段）+ 分支纠错重定向
// ============================================================================
// - Load 指令：在此阶段通过 DRAM 读端口读取数据
// - Store 指令：**不在此阶段写入** DRAM，只携带地址/数据/掩码信息向后传递，
//   延迟到 Commit 阶段才实际写入（避免推测执行污染内存）
// - 其他指令：直接透传 Execute 阶段的结果
//
// 新增：MEM 阶段输出分支纠错重定向信号（redirect）
//   - Execute 在上一阶段计算分支真值并标记 mispredict
//   - MEM 阶段读取 mispredict 标志，输出重定向到 PC 模块
//   - PC 优先级：MEM redirect > Fetch predict > sequential
// ============================================================================
class MEM extends Module {
  val in = IO(Flipped(Decoupled(new Execute_MEM_Payload)))  // 输入：来自 Execute
  val out = IO(Decoupled(new MEM_WB_Payload))               // 输出：送往 WB
  val io = IO(new Bundle {
    val ram_addr_o  = Output(UInt(32.W))          // DRAM 读地址（Load 使用）
    val ram_mask_o  = Output(UInt(3.W))           // 访存宽度掩码
    val ram_rdata_i = Input(UInt(32.W))           // DRAM 读回数据
  })
  val redirect = IO(new Bundle { // MEM 阶段重定向输出
    val valid  = Output(Bool())                        // 需要重定向
    val addr   = Output(UInt(32.W))                    // 正确的跳转目标地址
    val robIdx = Output(UInt(CPUConfig.robPtrWidth.W)) // 误预测指令的 ROB 指针（用于回滚）
  })

  // 从类型编码中提取各指令类型
  val uType = in.bits.type_decode_together(8)
  val jal   = in.bits.type_decode_together(7)
  val jalr  = in.bits.type_decode_together(6)
  val lType = in.bits.type_decode_together(4)  // Load
  val iType = in.bits.type_decode_together(3)
  val sType = in.bits.type_decode_together(2)  // Store
  val rType = in.bits.type_decode_together(1)

  // DRAM 读端口：只有 Load 指令才发起读请求
  io.ram_addr_o := Mux(lType, in.bits.data, 0.U)
  io.ram_mask_o := Mux(lType, in.bits.inst_funct3, 0.U)

  // ---- 重定向逻辑 ----
  // 当 Execute 阶段检测到分支预测错误时，MEM 阶段发出重定向信号
  redirect.valid  := in.valid && in.bits.mispredict
  redirect.addr   := in.bits.actual_target
  redirect.robIdx := in.bits.robIdx

  // ---- 输出结果打包 ----
  out.bits.pc := in.bits.pc
  out.bits.inst_rd := Mux(uType || jal || jalr || lType || iType || rType, in.bits.inst_rd, 0.U)
  out.bits.data := MuxCase(0.U, Seq(
    (uType || jal || jalr || iType || rType) -> in.bits.data,
    lType -> io.ram_rdata_i
  ))
  out.bits.type_decode_together := in.bits.type_decode_together
  out.bits.robIdx         := in.bits.robIdx
  out.bits.regWriteEnable := in.bits.regWriteEnable
  out.bits.isBranch       := in.bits.isBranch
  out.bits.isJump         := in.bits.isJump
  out.bits.predict_taken  := in.bits.predict_taken
  out.bits.predict_target := in.bits.predict_target
  out.bits.actual_taken   := in.bits.actual_taken
  out.bits.actual_target  := in.bits.actual_target
  out.bits.mispredict     := in.bits.mispredict
  out.bits.bht_meta       := in.bits.bht_meta

  // Store 信息携带
  out.bits.isStore    := sType
  out.bits.store_addr := Mux(sType, in.bits.data, 0.U)
  out.bits.store_data := Mux(sType, in.bits.reg_rdata2, 0.U)
  out.bits.store_mask := Mux(sType, in.bits.inst_funct3, 0.U)

  in.ready  := out.ready
  out.valid := in.valid
}
