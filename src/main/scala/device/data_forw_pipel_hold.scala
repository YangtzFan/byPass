import chisel3._

class data_forw_pipel_hold extends Module {
  val io = IO(new Bundle {
    val if_type_decode_together = Input(UInt(3.W))
    val if_inst = Input(UInt(32.W))
    val id_type_decode_together = Input(UInt(9.W))
    val id_inst = Input(UInt(32.W))
    val ex_type_decode_together = Input(UInt(9.W))
    val ex_inst = Input(UInt(32.W))
    val mem_type_decode_together = Input(UInt(9.W))
    val mem_inst_rd = Input(UInt(5.W))
    val wb_type_decode_together = Input(UInt(9.W))
    val wb_inst_rd = Input(UInt(5.W))

    val C_EX_A_ID_rs1 = Output(Bool())
    val C_EX_A_ID_rs2 = Output(Bool())
    val C_MEM_A_ID_rs1 = Output(Bool())
    val C_MEM_A_ID_rs2 = Output(Bool())
    val C_WB_A_ID_rs1 = Output(Bool())
    val C_WB_A_ID_rs2 = Output(Bool())

    val C_MEM_B_EX_rs2 = Output(Bool())
    val C_WB_B_EX_rs2 = Output(Bool())

    val C_WB_B_ID_rs2 = Output(Bool())
    val D_WB_B_ID_rs2 = Output(Bool())

    val C_EX_E_IF_rs1 = Output(Bool())
    val C_MEM_E_IF_rs1 = Output(Bool())
    val C_WB_E_IF_rs1 = Output(Bool())

    val D_MEM_A_ID_rs1 = Output(Bool())
    val D_MEM_A_ID_rs2 = Output(Bool())
    val D_WB_A_ID_rs1 = Output(Bool())
    val D_WB_A_ID_rs2 = Output(Bool())

    val D_MEM_B_EX_rs2 = Output(Bool())
    val D_WB_B_EX_rs2 = Output(Bool())

    val D_MEM_E_IF_rs1 = Output(Bool())
    val D_WB_E_IF_rs1 = Output(Bool())
    val hold_dff = Output(UInt(3.W))
  })

  val ifInstRs1 = io.if_inst(19, 15)
  val idInstRs1 = io.id_inst(19, 15)
  val idInstRs2 = io.id_inst(24, 20)
  val exInstRs2 = io.ex_inst(24, 20)
  val idInstRd = io.id_inst(11, 7)
  val exInstRd = io.ex_inst(11, 7)

  val ifJalr = io.if_type_decode_together(1)

  val idUType = io.id_type_decode_together(8)
  val idJal = io.id_type_decode_together(7)
  val idJalr = io.id_type_decode_together(6)
  val idBType = io.id_type_decode_together(5)
  val idLType = io.id_type_decode_together(4)
  val idIType = io.id_type_decode_together(3)
  val idSType = io.id_type_decode_together(2)
  val idRType = io.id_type_decode_together(1)

  val exUType = io.ex_type_decode_together(8)
  val exJal = io.ex_type_decode_together(7)
  val exJalr = io.ex_type_decode_together(6)
  val exLType = io.ex_type_decode_together(4)
  val exIType = io.ex_type_decode_together(3)
  val exSType = io.ex_type_decode_together(2)
  val exRType = io.ex_type_decode_together(1)

  val memUType = io.mem_type_decode_together(8)
  val memJal = io.mem_type_decode_together(7)
  val memJalr = io.mem_type_decode_together(6)
  val memLType = io.mem_type_decode_together(4)
  val memIType = io.mem_type_decode_together(3)
  val memRType = io.mem_type_decode_together(1)

  val wbUType = io.wb_type_decode_together(8)
  val wbJal = io.wb_type_decode_together(7)
  val wbJalr = io.wb_type_decode_together(6)
  val wbLType = io.wb_type_decode_together(4)
  val wbIType = io.wb_type_decode_together(3)
  val wbRType = io.wb_type_decode_together(1)

  val bId = idSType
  val cId = idUType || idJal || idJalr || idIType || idRType
  val cEx = exUType || exJal || exJalr || exIType || exRType
  val cMem = memUType || memJal || memJalr || memIType || memRType
  val cWb = wbUType || wbJal || wbJalr || wbIType || wbRType
  val dId = idLType
  val dEx = exLType
  val dMem = memLType
  val dWb = wbLType
  val aId = idBType || idLType || idSType || idIType || idRType
  val aIdWithoutS = idBType || idLType || idIType || idRType
  val bEx = exSType
  val eIf = ifJalr
  val eId = idJalr

  io.C_EX_A_ID_rs1 := cEx && aId && (idInstRs1 === exInstRd) && (idInstRs1 =/= 0.U)
  io.C_EX_A_ID_rs2 := cEx && aIdWithoutS && (idInstRs2 === exInstRd) && (idInstRs2 =/= 0.U)
  io.C_MEM_A_ID_rs1 := cMem && aId && (idInstRs1 === io.mem_inst_rd) && (idInstRs1 =/= 0.U)
  io.C_MEM_A_ID_rs2 := cMem && aIdWithoutS && (idInstRs2 === io.mem_inst_rd) && (idInstRs2 =/= 0.U)
  io.C_WB_A_ID_rs1 := cWb && aId && (idInstRs1 === io.wb_inst_rd) && (idInstRs1 =/= 0.U)
  io.C_WB_A_ID_rs2 := cWb && aIdWithoutS && (idInstRs2 === io.wb_inst_rd) && (idInstRs2 =/= 0.U)

  io.C_MEM_B_EX_rs2 := cMem && bEx && (exInstRs2 === io.mem_inst_rd) && (exInstRs2 =/= 0.U)
  io.C_WB_B_EX_rs2 := cWb && bEx && (exInstRs2 === io.wb_inst_rd) && (exInstRs2 =/= 0.U)

  io.C_WB_B_ID_rs2 := cWb && bId && (idInstRs2 === io.wb_inst_rd) && (idInstRs2 =/= 0.U)
  io.D_WB_B_ID_rs2 := dWb && bId && (idInstRs2 === io.wb_inst_rd) && (idInstRs2 =/= 0.U)

  io.C_EX_E_IF_rs1 := cEx && eIf && (ifInstRs1 === exInstRd) && (ifInstRs1 =/= 0.U)
  io.C_MEM_E_IF_rs1 := cMem && eIf && (ifInstRs1 === io.mem_inst_rd) && (ifInstRs1 =/= 0.U)
  io.C_WB_E_IF_rs1 := cWb && eIf && (ifInstRs1 === io.wb_inst_rd) && (ifInstRs1 =/= 0.U)

  io.D_MEM_A_ID_rs1 := dMem && aId && (idInstRs1 === io.mem_inst_rd) && (idInstRs1 =/= 0.U)
  io.D_MEM_A_ID_rs2 := dMem && aIdWithoutS && (idInstRs2 === io.mem_inst_rd) && (idInstRs2 =/= 0.U)
  io.D_WB_A_ID_rs1 := dWb && aId && (idInstRs1 === io.wb_inst_rd) && (idInstRs1 =/= 0.U)
  io.D_WB_A_ID_rs2 := dWb && aIdWithoutS && (idInstRs2 === io.wb_inst_rd) && (idInstRs2 =/= 0.U)

  io.D_MEM_B_EX_rs2 := dMem && bEx && (exInstRs2 === io.mem_inst_rd) && (exInstRs2 =/= 0.U)
  io.D_WB_B_EX_rs2 := dWb && bEx && (exInstRs2 === io.wb_inst_rd) && (exInstRs2 =/= 0.U)

  io.D_MEM_E_IF_rs1 := dMem && eIf && (ifInstRs1 === io.mem_inst_rd) && (ifInstRs1 =/= 0.U)
  io.D_WB_E_IF_rs1 := dWb && eIf && (ifInstRs1 === io.wb_inst_rd) && (ifInstRs1 =/= 0.U)

  val hold1 = eId
  val hold2 = (dId && eIf && (ifInstRs1 === idInstRd) && (ifInstRs1 =/= 0.U)) ||
    (dEx && eIf && (ifInstRs1 === exInstRd) && (ifInstRs1 =/= 0.U)) ||
    (cId && eIf && (ifInstRs1 === idInstRd) && (ifInstRs1 =/= 0.U))
  val hold3 = (dEx && aId && (idInstRs1 === exInstRd) && (idInstRs1 =/= 0.U)) ||
    (dEx && aIdWithoutS && (idInstRs2 === exInstRd) && (idInstRs2 =/= 0.U))

  io.hold_dff := Mux(hold1, 1.U, Mux(hold2, 2.U, Mux(hold3, 3.U, 0.U)))
}
