import chisel3._

class myCPU extends Module {
  val io = IO(new Bundle {
    val if_pc_o = Output(UInt(32.W))
    val if_inst_i = Input(UInt(32.W))

    val ram_addr_o = Output(UInt(32.W))
    val ram_wen_o = Output(Bool())
    val ram_mask = Output(UInt(3.W))
    val ram_wdata_o = Output(UInt(32.W))
    val ram_rdata_i = Input(UInt(32.W))

    val wb_inst_bubble_ifornot = Output(Bool())
    val wb_inst_addr_i = Output(UInt(32.W))
    val wb_reg_wen = Output(Bool())
    val wb_reg_waddr = Output(UInt(5.W))
    val wb_reg_wdata = Output(UInt(32.W))
  })

  val cExAIdRs1 = Wire(Bool())
  val cExAIdRs2 = Wire(Bool())
  val cMemAIdRs1 = Wire(Bool())
  val cMemAIdRs2 = Wire(Bool())
  val cWbAIdRs1 = Wire(Bool())
  val cWbAIdRs2 = Wire(Bool())
  val cMemBExRs2 = Wire(Bool())
  val cWbBExRs2 = Wire(Bool())
  val cWbBIdRs2 = Wire(Bool())
  val dWbBIdRs2 = Wire(Bool())
  val cExEIfRs1 = Wire(Bool())
  val cMemEIfRs1 = Wire(Bool())
  val cWbEIfRs1 = Wire(Bool())
  val dMemAIdRs1 = Wire(Bool())
  val dMemAIdRs2 = Wire(Bool())
  val dWbAIdRs1 = Wire(Bool())
  val dWbAIdRs2 = Wire(Bool())
  val dMemBExRs2 = Wire(Bool())
  val dWbBExRs2 = Wire(Bool())
  val dMemEIfRs1 = Wire(Bool())
  val dWbEIfRs1 = Wire(Bool())

  val ifJumpPredictAddr = Wire(UInt(32.W))
  val exPipelineFlushAddr = Wire(UInt(32.W))
  val ifJumpEnable = Wire(Bool())
  val exPipelineFlushEnable = Wire(Bool())
  val holdDff = Wire(UInt(3.W))

  val uPc = Module(new PC)
  uPc.io.jump_predict_addr := ifJumpPredictAddr
  uPc.io.pipeline_flush_addr := exPipelineFlushAddr
  uPc.io.jump_enable := ifJumpEnable
  uPc.io.pipeline_flush_enable := exPipelineFlushEnable
  uPc.io.if_hold_dff := holdDff
  io.if_pc_o := uPc.io.pc_o

  val uIF = Module(new IF)
  uIF.io.inst_i := io.if_inst_i
  val ifTypeDecodeTogether = uIF.io.type_decode_together_o

  val ifRegRdata1 = Wire(UInt(32.W))
  val idRegRdata1 = Wire(UInt(32.W))
  val exData = Wire(UInt(32.W))
  val memData = Wire(UInt(32.W))
  val ifRegRdata1ForwardBit = Wire(Bool())
  val idRegRdata1ForwardBit = Wire(Bool())

  ifRegRdata1 := Mux(cExEIfRs1, exData, 0.U) |
    Mux(cMemEIfRs1 || dMemEIfRs1, memData, 0.U) |
    Mux(cWbEIfRs1 || dWbEIfRs1, io.wb_reg_wdata, 0.U)
  ifRegRdata1ForwardBit := cExEIfRs1 || cMemEIfRs1 || cWbEIfRs1 || dMemEIfRs1 || dWbEIfRs1

  val uIfIdDff = Module(new IF_ID_dff)
  uIfIdDff.io.inst_addr_i := io.if_pc_o
  uIfIdDff.io.inst_i := io.if_inst_i
  uIfIdDff.io.reg_rdata1_i := ifRegRdata1
  uIfIdDff.io.reg_rdata1_forward_bit_i := ifRegRdata1ForwardBit
  uIfIdDff.io.id_hold_dff := holdDff
  uIfIdDff.io.pipeline_flush_enable_i := exPipelineFlushEnable

  val idInstAddr = uIfIdDff.io.inst_addr_o
  val idInst = uIfIdDff.io.inst_o
  idRegRdata1 := uIfIdDff.io.reg_rdata1_o
  idRegRdata1ForwardBit := uIfIdDff.io.reg_rdata1_forward_bit_o

  val uID = Module(new ID)
  uID.io.inst_addr_i := idInstAddr
  uID.io.inst_i := idInst
  val idRegRaddr1 = uID.io.reg_raddr1_o
  val idRegRaddr2 = uID.io.reg_raddr2_o
  val idImm = uID.io.imm_o
  val idTypeDecodeTogether = uID.io.type_decode_together_o

  val uREG = Module(new REG)
  uREG.io.reg_wen_i := io.wb_reg_wen
  uREG.io.reg_waddr_i := io.wb_reg_waddr
  uREG.io.reg_wdata_i := io.wb_reg_wdata
  uREG.io.reg_raddr1_i := idRegRaddr1
  uREG.io.reg_raddr2_i := idRegRaddr2

  val idRegRdata1O = Wire(UInt(32.W))
  val idRegRdata2O = Wire(UInt(32.W))
  idRegRdata1O := Mux(
    cExAIdRs1,
    exData,
    Mux(cMemAIdRs1 || dMemAIdRs1, memData, Mux(cWbAIdRs1 || dWbAIdRs1, io.wb_reg_wdata, uREG.io.reg_rdata1_o))
  )
  idRegRdata2O := Mux(
    cExAIdRs2,
    exData,
    Mux(
      cMemAIdRs2 || dMemAIdRs2,
      memData,
      Mux(cWbAIdRs2 || dWbAIdRs2 || cWbBIdRs2 || dWbBIdRs2, io.wb_reg_wdata, uREG.io.reg_rdata2_o)
    )
  )

  val uIdExDff = Module(new ID_EX_dff)
  uIdExDff.io.inst_addr_i := idInstAddr
  uIdExDff.io.inst_i := idInst
  uIdExDff.io.reg_rdata1_i := idRegRdata1O
  uIdExDff.io.reg_rdata2_i := idRegRdata2O
  uIdExDff.io.imm_i := idImm
  uIdExDff.io.type_decode_together_i := idTypeDecodeTogether
  uIdExDff.io.ex_hold_dff := holdDff
  uIdExDff.io.pipeline_flush_enable_i := exPipelineFlushEnable

  val exInstAddr = uIdExDff.io.inst_addr_o
  val exInst = uIdExDff.io.inst_o
  val exRegRdata1 = uIdExDff.io.reg_rdata1_o
  val exRegRdata2 = uIdExDff.io.reg_rdata2_o
  val exImm = uIdExDff.io.imm_o
  val exTypeDecodeTogether = uIdExDff.io.type_decode_together_o

  val uEX = Module(new EX)
  uEX.io.inst_addr_i := exInstAddr
  uEX.io.inst_i := exInst
  uEX.io.reg_rdata1_i := exRegRdata1
  uEX.io.reg_rdata2_i := exRegRdata2
  uEX.io.imm_i := exImm
  uEX.io.type_decode_together_i := exTypeDecodeTogether

  val exInstFunct3 = uEX.io.inst_funct3_o
  val exInstRd = uEX.io.inst_rd_o
  exData := uEX.io.data_o
  val exRegRdata2O1 = uEX.io.reg_rdata2_o
  val exAluZero = uEX.io.alu_zero_o
  val exTypeDecodeTogetherO = uEX.io.type_decode_together_o

  val exRegRdata2O = Mux(cMemBExRs2 || dMemBExRs2, memData, Mux(cWbBExRs2 || dWbBExRs2, io.wb_reg_wdata, exRegRdata2O1))

  val uExMemDff = Module(new EX_MEX_dff)
  uExMemDff.io.inst_addr_i := exInstAddr
  uExMemDff.io.inst_funct3_i := exInstFunct3
  uExMemDff.io.inst_rd_i := exInstRd
  uExMemDff.io.data_i := exData
  uExMemDff.io.reg_rdata2_i := exRegRdata2O
  uExMemDff.io.type_decode_together_i := exTypeDecodeTogetherO
  uExMemDff.io.mem_hold_dff := holdDff

  val memInstAddr = uExMemDff.io.inst_addr_o
  val memInstFunct3 = uExMemDff.io.inst_funct3_o
  val memInstRdI = uExMemDff.io.inst_rd_o
  val memDataI = uExMemDff.io.data_o
  val memRegRdata2 = uExMemDff.io.reg_rdata2_o
  val memTypeDecodeTogetherI = uExMemDff.io.type_decode_together_o

  val uMEM = Module(new MEM)
  uMEM.io.inst_funct3_i := memInstFunct3
  uMEM.io.inst_rd_i := memInstRdI
  uMEM.io.data_i := memDataI
  uMEM.io.reg_rdata2_i := memRegRdata2
  uMEM.io.type_decode_together_i := memTypeDecodeTogetherI
  uMEM.io.ram_rdata_i := io.ram_rdata_i

  io.ram_addr_o := uMEM.io.ram_addr_o
  io.ram_wdata_o := uMEM.io.ram_wdata_o
  io.ram_wen_o := uMEM.io.ram_wen_o
  io.ram_mask := uMEM.io.ram_mask_o

  val memInstRdO = uMEM.io.inst_rd_o
  memData := uMEM.io.data_o
  val memTypeDecodeTogetherO = uMEM.io.type_decode_together_o

  val uMemWbDff = Module(new MEM_WB_dff)
  uMemWbDff.io.inst_addr_i := memInstAddr
  uMemWbDff.io.inst_rd_i := memInstRdO
  uMemWbDff.io.data_i := memData
  uMemWbDff.io.type_decode_together_i := memTypeDecodeTogetherO
  uMemWbDff.io.wb_hold_dff := holdDff

  io.wb_inst_addr_i := uMemWbDff.io.inst_addr_o
  io.wb_reg_waddr := uMemWbDff.io.inst_rd_o
  io.wb_reg_wdata := uMemWbDff.io.data_o
  val wbTypeDecodeTogetherI = uMemWbDff.io.type_decode_together_o

  val uWB = Module(new WB)
  uWB.io.type_decode_together_i := wbTypeDecodeTogetherI
  io.wb_inst_bubble_ifornot := uWB.io.inst_bubble_ifornot
  io.wb_reg_wen := uWB.io.reg_wen

  val uDataForwPipelHold = Module(new data_forw_pipel_hold)
  uDataForwPipelHold.io.if_type_decode_together := ifTypeDecodeTogether
  uDataForwPipelHold.io.if_inst := io.if_inst_i
  uDataForwPipelHold.io.id_type_decode_together := idTypeDecodeTogether
  uDataForwPipelHold.io.id_inst := idInst
  uDataForwPipelHold.io.ex_type_decode_together := exTypeDecodeTogether
  uDataForwPipelHold.io.ex_inst := exInst
  uDataForwPipelHold.io.mem_type_decode_together := memTypeDecodeTogetherI
  uDataForwPipelHold.io.mem_inst_rd := memInstRdI
  uDataForwPipelHold.io.wb_type_decode_together := wbTypeDecodeTogetherI
  uDataForwPipelHold.io.wb_inst_rd := io.wb_reg_waddr

  cExAIdRs1 := uDataForwPipelHold.io.C_EX_A_ID_rs1
  cExAIdRs2 := uDataForwPipelHold.io.C_EX_A_ID_rs2
  cMemAIdRs1 := uDataForwPipelHold.io.C_MEM_A_ID_rs1
  cMemAIdRs2 := uDataForwPipelHold.io.C_MEM_A_ID_rs2
  cWbAIdRs1 := uDataForwPipelHold.io.C_WB_A_ID_rs1
  cWbAIdRs2 := uDataForwPipelHold.io.C_WB_A_ID_rs2
  cMemBExRs2 := uDataForwPipelHold.io.C_MEM_B_EX_rs2
  cWbBExRs2 := uDataForwPipelHold.io.C_WB_B_EX_rs2
  cWbBIdRs2 := uDataForwPipelHold.io.C_WB_B_ID_rs2
  dWbBIdRs2 := uDataForwPipelHold.io.D_WB_B_ID_rs2
  cExEIfRs1 := uDataForwPipelHold.io.C_EX_E_IF_rs1
  cMemEIfRs1 := uDataForwPipelHold.io.C_MEM_E_IF_rs1
  cWbEIfRs1 := uDataForwPipelHold.io.C_WB_E_IF_rs1
  dMemAIdRs1 := uDataForwPipelHold.io.D_MEM_A_ID_rs1
  dMemAIdRs2 := uDataForwPipelHold.io.D_MEM_A_ID_rs2
  dWbAIdRs1 := uDataForwPipelHold.io.D_WB_A_ID_rs1
  dWbAIdRs2 := uDataForwPipelHold.io.D_WB_A_ID_rs2
  dMemBExRs2 := uDataForwPipelHold.io.D_MEM_B_EX_rs2
  dWbBExRs2 := uDataForwPipelHold.io.D_WB_B_EX_rs2
  dMemEIfRs1 := uDataForwPipelHold.io.D_MEM_E_IF_rs1
  dWbEIfRs1 := uDataForwPipelHold.io.D_WB_E_IF_rs1
  holdDff := uDataForwPipelHold.io.hold_dff

  val idJalrRs1Data = Mux(idRegRdata1ForwardBit, idRegRdata1, uREG.io.reg_rdata1_o)

  val uJumpPredict = Module(new jump_predict)
  uJumpPredict.io.if_inst_addr := io.if_pc_o
  uJumpPredict.io.if_inst := io.if_inst_i
  uJumpPredict.io.if_type_decode_together := ifTypeDecodeTogether
  uJumpPredict.io.id_inst := idInst
  uJumpPredict.io.id_jalr_rs1_data := idJalrRs1Data
  uJumpPredict.io.id_type_decode_together := idTypeDecodeTogether
  uJumpPredict.io.ex_type_decode_together := exTypeDecodeTogether
  uJumpPredict.io.ex_alu_zero := exAluZero
  uJumpPredict.io.ex_inst := exInst
  uJumpPredict.io.ex_inst_addr := exInstAddr
  uJumpPredict.io.ex_imm := exImm
  uJumpPredict.io.mem_inst_addr := memInstAddr

  ifJumpPredictAddr := uJumpPredict.io.if_jump_predict_addr_o
  exPipelineFlushAddr := uJumpPredict.io.ex_pipeline_flush_addr_o
  ifJumpEnable := uJumpPredict.io.if_jump_enable_o
  exPipelineFlushEnable := uJumpPredict.io.ex_pipeline_flush_enable_o
}
