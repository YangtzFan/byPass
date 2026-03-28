package mycpu

import chisel3._
import chisel3.util.log2Ceil
import mycpu.dataLane._
import mycpu.device._

class myCPU extends Module {
  val io = IO(new Bundle {
    val inst_addr_o = Output(UInt(32.W))
    val inst_i = Input(UInt(32.W))

    val ram_addr_o = Output(UInt(32.W))
    val ram_wen_o = Output(Bool())
    val ram_mask_o = Output(UInt(3.W))
    val ram_wdata_o = Output(UInt(32.W))
    val ram_rdata_i = Input(UInt(32.W))

    val wb_inst_bubble_ifornot = Output(Bool())
    val wb_inst_addr = Output(UInt(32.W))
    val wb_reg_wen = Output(Bool())
    val wb_reg_waddr = Output(UInt(5.W))
    val wb_reg_wdata = Output(UInt(32.W))
  })

  val idJumpEnable = Wire(Bool())
  val idJumpPredictAddr = Wire(UInt(32.W))
  val exPipelineFlushEnable = Wire(Bool())
  val exPipelineFlushAddr = Wire(UInt(32.W))

  // ----------------------------------

  val uPc = Module(new PC)
  uPc.in.jump_enable := idJumpEnable
  uPc.in.jump_predict_addr := idJumpPredictAddr
  uPc.in.pipeline_flush_enable := exPipelineFlushEnable
  uPc.in.pipeline_flush_addr := exPipelineFlushAddr

  // ------------ IF - BEG ------------

  val uIF = Module(new IF)
  uIF.in <> uPc.out // DecoupledIO 耦合上一级
  io.inst_addr_o := uIF.io.inst_addr_o
  uIF.io.inst_i := io.inst_i

  // ------------ IF - END ------------

  val uIfIdDff = Module(new BaseDff(new IF_ID_Payload, supportFlush = true))
  uIfIdDff.in <> uIF.out // DecoupledIO 耦合上一级
  uIfIdDff.flush.get := exPipelineFlushEnable || idJumpEnable // 预测跳转时，冲刷本流水寄存器，防止取一条不相关的指令

  // ------------ ID - BEG ------------

  val uID = Module(new ID)
  uID.in <> uIfIdDff.out // DecoupledIO 耦合上一级
  val uREG = Module(new REG)
  uREG.io.reg_raddr1_i := uID.io.reg_raddr1_o
  uREG.io.reg_raddr2_i := uID.io.reg_raddr2_o
  uID.io.reg_rdata1_i  := uREG.io.reg_rdata1_o
  uID.io.reg_rdata2_i  := uREG.io.reg_rdata2_o
  uREG.io.reg_waddr_i  := io.wb_reg_waddr
  uREG.io.reg_wdata_i  := io.wb_reg_wdata
  uREG.io.reg_wen_i    := io.wb_reg_wen

  // ---- 分支预测器（根据 CPUConfig 选择性生成）----
  val uBHT = Option.when(CPUConfig.useBHT)(Module(new BHT(CPUConfig.bhtEntries)))
  if (CPUConfig.useBHT) {
    uBHT.get.io.read_idx := uIfIdDff.out.bits.inst_addr(log2Ceil(CPUConfig.bhtEntries) + 1, 2)
    uID.bht_predict_taken.get := uBHT.get.io.predict_taken
  }

  // ------------ ID - END ------------

  val uIdExDff = Module(new BaseDff(new ID_EX_Payload, supportFlush = true))

  // ---- Load-Use 停顿检测 ----
  // 当 EX 级指令是 Load 且其 rd 与 ID 级指令的 rs1/rs2 相同时，
  // Load 数据要到 MEM 才可用，必须停顿 1 拍等数据出来再转发。
  val lType_EX = uIdExDff.out.bits.type_decode_together(4) // EX 级的指令是否为 Load
  val rd_EX = uIdExDff.out.bits.inst(11, 7)                // EX 级指令的目标寄存器
  val loadUseStall = uIdExDff.out.valid && lType_EX && (rd_EX =/= 0.U) &&
    ((uID.io.use_rs1_o && (uID.io.reg_raddr1_o === rd_EX)) ||
     (uID.io.use_rs2_o && (uID.io.reg_raddr2_o === rd_EX)))

  // Backpressure control: insert bubble into ID/EX and hold upstream
  // 停顿时: ID/EX 收到 valid=false → 插入气泡；ID 不接收新数据 → 反压至 PC
  uIdExDff.in.bits := uID.out.bits
  uIdExDff.in.valid := uID.out.valid && !loadUseStall
  uID.out.ready := uIdExDff.in.ready && !loadUseStall

  uIdExDff.flush.get := exPipelineFlushEnable

  // ------------ EX - BEG ------------

  val uEX = Module(new EX)
  uEX.in <> uIdExDff.out

  // BHT 更新：EX 阶段 B-type 分支解析结果写回 BHT（仅 DynamicBHT 模式）
  if(CPUConfig.useBHT){
    uBHT.get.io.update_valid := uEX.bht_update.get.valid
    uBHT.get.io.update_idx   := uEX.bht_update.get.idx
    uBHT.get.io.update_taken := uEX.bht_update.get.taken
  }

  // ------------ EX - END ------------

  val uExMemDff = Module(new BaseDff(new EX_MEM_Payload, supportFlush = false))
  uExMemDff.in <> uEX.out // DecoupledIO 耦合上一级

  // ------------ MEM - BEG -----------

  val uMEM = Module(new MEM)
  uMEM.in <> uExMemDff.out // DecoupledIO 耦合上一级
  io.ram_addr_o  := uMEM.io.ram_addr_o
  io.ram_mask_o  := uMEM.io.ram_mask_o
  io.ram_wdata_o := uMEM.io.ram_wdata_o
  io.ram_wen_o   := uMEM.io.ram_wen_o
  uMEM.io.ram_rdata_i := io.ram_rdata_i

  // ------------ MEM - END -----------

  val uMemWbDff = Module(new BaseDff(new MEM_WB_Payload, supportFlush = false))
  uMemWbDff.in <> uMEM.out // DecoupledIO 耦合上一级

  // ------------ WB - BEG ------------

  val uWB = Module(new WB)
  uWB.in <> uMemWbDff.out // DecoupledIO 耦合上一级
  io.wb_inst_bubble_ifornot := uWB.io.inst_bubble_ifornot
  io.wb_inst_addr := uWB.io.inst_addr
  io.wb_reg_wen := uWB.io.reg_wen
  io.wb_reg_waddr := uWB.io.inst_rd
  io.wb_reg_wdata := uWB.io.data

  // ------------ WB - END ------------

  // ---- 数据旁路转发连接 ----
  // 将 EX/MEM 和 MEM/WB 级间寄存器中的写寄存器信息转发给 EX 级，避免因前序指令尚未写回寄存器堆而读到旧值。

  // EX/MEM 转发源：前 1 条指令的结果（不含 Load，因其数据要等 MEM 读内存）
  val td_MEM = uExMemDff.out.bits.type_decode_together
  val wen_MEM = uExMemDff.out.valid && (td_MEM(8) || td_MEM(7) || td_MEM(6) || td_MEM(3) || td_MEM(1))
    // uType(8), jal(7), jalr(6), iType(3), rType(1) — ALU 结果在 EX 级已就绪

  uEX.fwd.mem_rd   := uExMemDff.out.bits.inst_rd
  uEX.fwd.mem_data := uExMemDff.out.bits.data
  uEX.fwd.mem_wen  := wen_MEM

  // MEM/WB 转发源：前 2 条指令的结果（含 Load，MEM 读出内存后数据已就绪）
  val memWbTd = uMemWbDff.out.bits.type_decode_together
  val memWbWen = uMemWbDff.out.valid &&
    (memWbTd(8) || memWbTd(7) || memWbTd(6) || memWbTd(4) || memWbTd(3) || memWbTd(1))
    // 增加 lType(4)，此时 Load 数据已从内存读出

  uEX.fwd.wb_rd   := uMemWbDff.out.bits.inst_rd
  uEX.fwd.wb_data := uMemWbDff.out.bits.data
  uEX.fwd.wb_wen  := memWbWen

  // ---- 分支预测与流水线冲刷连接 ----
  // ID 阶段预测跳转。重定向 PC 并冲刷 IF/ID 中的顺序取指
  // 仅在分支指令成功离开 ID 级（fire）时生效，避免停顿时重复预测
  idJumpEnable := uID.io.predict_jump && uIdExDff.in.fire && !exPipelineFlushEnable
  idJumpPredictAddr := uID.io.predict_target
  // EX 阶段验证分支结果，预测错误或 JALR 时冲刷流水线
  exPipelineFlushEnable := uEX.flush_io.enable
  exPipelineFlushAddr := uEX.flush_io.redirect_addr
}
