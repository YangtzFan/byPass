import chisel3._
import chisel3.util._

class jump_predict extends Module {
  val io = IO(new Bundle {
    val if_inst_addr = Input(UInt(32.W))
    val if_inst = Input(UInt(32.W))
    val if_type_decode_together = Input(UInt(3.W))
    val id_inst = Input(UInt(32.W))
    val id_jalr_rs1_data = Input(UInt(32.W))
    val id_type_decode_together = Input(UInt(9.W))
    val ex_type_decode_together = Input(UInt(9.W))
    val ex_alu_zero = Input(Bool())
    val ex_inst = Input(UInt(32.W))
    val ex_inst_addr = Input(UInt(32.W))
    val ex_imm = Input(UInt(32.W))
    val mem_inst_addr = Input(UInt(32.W))

    val if_jump_predict_addr_o = Output(UInt(32.W))
    val ex_pipeline_flush_addr_o = Output(UInt(32.W))
    val if_jump_enable_o = Output(Bool())
    val ex_pipeline_flush_enable_o = Output(Bool())
  })

  val ifJal = io.if_type_decode_together(2)
  val idJalr = io.id_type_decode_together(6)
  val ifBTypeRaw = io.if_type_decode_together(0)

  val exBType = io.ex_type_decode_together(5)
  val exInstFunct3 = io.ex_inst(14, 12)
  val exJumpIfOrNot = exBType && (exInstFunct3(2) === (io.ex_alu_zero ^ exInstFunct3(0)))

  val memJumpIfOrNot = RegInit(false.B)
  val jumpPhtMemBType = RegInit(false.B)
  memJumpIfOrNot := exJumpIfOrNot
  jumpPhtMemBType := ifBTypeRaw && exBType

  val jumpPhtIfBType = ifBTypeRaw
  val jumpPhtExBType = exBType && !ifBTypeRaw

  val jumpPhtAddr = Wire(UInt(7.W))
  jumpPhtAddr := Mux(
    jumpPhtIfBType,
    io.if_inst_addr(8, 2),
    (Fill(7, jumpPhtExBType) & io.ex_inst_addr(8, 2)) | (Fill(7, jumpPhtMemBType) & io.mem_inst_addr(8, 2))
  )

  val jumpIfOrNotWdata = (jumpPhtExBType && exJumpIfOrNot) || (jumpPhtMemBType && memJumpIfOrNot)

  val uJumpPHT = Module(new jump_PHT)
  uJumpPHT.io.a := jumpPhtAddr
  uJumpPHT.io.we := jumpPhtExBType || jumpPhtMemBType

  val jumpPhtDataIn = Cat(jumpIfOrNotWdata, uJumpPHT.io.spo(5, 1))
  uJumpPHT.io.d := jumpPhtDataIn

  val uCounter = Module(new jump_predict_counter)
  uCounter.io.a := uJumpPHT.io.spo
  uCounter.io.we := uJumpPHT.io.we
  val counterDataIn1 = uCounter.io.spo(1) ^ jumpIfOrNotWdata
  val counterDataIn0 = (uCounter.io.spo(1) && !uCounter.io.spo(0)) || (uCounter.io.spo(0) && jumpIfOrNotWdata)
  uCounter.io.d := Cat(counterDataIn1, counterDataIn0)

  val ifBType = ifBTypeRaw && uCounter.io.spo(1)

  val ifAdder11 = Mux(idJalr, io.id_jalr_rs1_data, Mux(ifJal || ifBType, io.if_inst_addr, 0.U))
  val ifAdder12 = Mux(
    idJalr,
    Cat(Fill(20, io.id_inst(31)), io.id_inst(31, 20)),
    (Fill(32, ifJal) & Cat(Fill(12, io.if_inst(31)), io.if_inst(19, 12), io.if_inst(20), io.if_inst(30, 21), 0.U(1.W))) |
      (Fill(32, ifBType) & Cat(Fill(20, io.if_inst(31)), io.if_inst(7), io.if_inst(30, 25), io.if_inst(11, 8), 0.U(1.W)))
  )

  val ifBTypeInIdReg = RegInit(false.B)
  val ifBTypeInExReg = RegInit(false.B)
  ifBTypeInIdReg := ifBType
  ifBTypeInExReg := ifBTypeInIdReg
  val ifBTypeInEx = ifBTypeInExReg

  io.ex_pipeline_flush_enable_o := exBType && (ifBTypeInEx ^ exJumpIfOrNot)

  val exAdder31 = Fill(32, io.ex_pipeline_flush_enable_o) & io.ex_inst_addr
  val exAdder32 = Fill(32, io.ex_pipeline_flush_enable_o) & Mux(ifBTypeInEx, 4.U(32.W), io.ex_imm)

  io.if_jump_predict_addr_o := ifAdder11 + ifAdder12
  io.ex_pipeline_flush_addr_o := exAdder31 + exAdder32
  io.if_jump_enable_o := io.ex_pipeline_flush_enable_o || ifJal || ifBType || idJalr
}
