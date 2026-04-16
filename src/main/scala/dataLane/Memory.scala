package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// Memory（访存阶段，原 MEM）+ 分支纠错重定向 + StoreBuffer 转发
// ============================================================================
// 无寄存器重命名版本：重定向不再需要 checkpointIdx，只重定向 PC + 回滚 ROB/SB
// ============================================================================
class Memory extends Module {
  val in  = IO(Flipped(Decoupled(new Execute_Memory_Payload)))  // 输入：来自 ExMemDff
  val out = IO(Decoupled(new Memory_Refresh_Payload))           // 输出：送往 MemRefDff → Refresh

  // ---- DRAM 读端口接口（Load 使用）----
  val io = IO(new Bundle {
    val ram_addr_o  = Output(UInt(32.W))          // DRAM 读地址
    val ram_mask_o  = Output(UInt(3.W))           // 访存宽度掩码
    val ram_rdata_i = Input(UInt(32.W))           // DRAM 读回数据
  })

  // ---- Memory 阶段重定向输出（无寄存器重命名版本：无 checkpointIdx）----
  val redirect = IO(new Bundle {
    val valid        = Output(Bool())                              // 需要重定向
    val addr         = Output(UInt(32.W))                          // 正确的跳转目标地址
    val robIdx       = Output(UInt(CPUConfig.robPtrWidth.W))       // 误预测指令的 ROB 指针（用于 ROB 回滚）
    val storeSeqSnap = Output(UInt(CPUConfig.storeSeqWidth.W))     // 误预测指令的 storeSeqSnap（用于 StoreBuffer 精确回滚）
  })

  // ---- StoreBuffer 写入端口 ----
  val sbWrite = IO(Output(new SBWriteIO))

  // ---- StoreBuffer 查询接口 ----
  val sbQuery = IO(new SBQueryIO)

  // ---- 外部 Store drain 信号 ----
  val drainActive = IO(Input(Bool()))

  // 指令类型提取
  val uType = in.bits.type_decode_together(8)
  val jal   = in.bits.type_decode_together(7)
  val jalr  = in.bits.type_decode_together(6)
  val bType = in.bits.type_decode_together(5)
  val lType = in.bits.type_decode_together(4)
  val iType = in.bits.type_decode_together(3)
  val sType = in.bits.type_decode_together(2)
  val rType = in.bits.type_decode_together(1)
  val other = in.bits.type_decode_together(0)

  // ---- StoreBuffer 写入（Store 指令）----
  sbWrite.valid := in.valid && sType && in.bits.isSbAlloc
  sbWrite.idx   := in.bits.sbIdx
  sbWrite.addr  := in.bits.data
  sbWrite.data  := in.bits.reg_rdata2
  sbWrite.mask  := in.bits.inst_funct3

  // ---- StoreBuffer 查询（Load 指令）----
  sbQuery.valid        := in.valid && lType
  sbQuery.addr         := in.bits.data
  sbQuery.storeSeqSnap := in.bits.storeSeqSnap

  // ---- Store-to-Load 冒险检测 ----
  val sbStall = sbQuery.hit || sbQuery.pending

  // ---- DRAM 端口冲突停顿 ----
  val dramConflict = lType && drainActive

  // 综合停顿信号
  val memStall = sbStall || dramConflict

  // DRAM 读端口
  io.ram_addr_o := Mux(lType && !sbQuery.hit, in.bits.data, 0.U)
  io.ram_mask_o := Mux(lType, in.bits.inst_funct3, 0.U)

  // ---- 重定向逻辑（无寄存器重命名版本：无 checkpointIdx）----
  redirect.valid        := in.valid && in.bits.mispredict && !memStall
  redirect.addr         := in.bits.actual_target
  redirect.robIdx       := in.bits.robIdx
  redirect.storeSeqSnap := in.bits.storeSeqSnap

  // ---- Load 数据来源选择 ----
  val loadData = Mux(sbQuery.hit, sbQuery.data, io.ram_rdata_i)

  // ---- 输出结果打包 ----
  out.bits.pc := in.bits.pc
  out.bits.inst_rd := Mux(uType || jal || jalr || lType || iType || rType, in.bits.inst_rd, 0.U)
  out.bits.data := Mux1H(Seq(
    (uType || jal || jalr || iType || rType) -> in.bits.data,
    lType -> loadData,
    (bType || other) -> 0.U(32.W)
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

  // ---- 握手信号 ----
  in.ready  := out.ready && !memStall
  out.valid := in.valid && !memStall
}
