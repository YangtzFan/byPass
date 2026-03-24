import chisel3._
import chisel3.util._
import chisel3.util._

class EX extends Module {
  val io = IO(new Bundle {
    val inst_addr_i = Input(UInt(32.W))
    val inst_i = Input(UInt(32.W))
    val reg_rdata1_i = Input(UInt(32.W))
    val reg_rdata2_i = Input(UInt(32.W))
    val imm_i = Input(UInt(32.W))
    val type_decode_together_i = Input(UInt(9.W))

    val inst_funct3_o = Output(UInt(3.W))
    val inst_rd_o = Output(UInt(5.W))
    val data_o = Output(UInt(32.W))
    val reg_rdata2_o = Output(UInt(32.W))
    val alu_zero_o = Output(Bool())
    val type_decode_together_o = Output(UInt(9.W))
  })

  val funct3 = io.inst_i(14, 12)
  val rd = io.inst_i(11, 7)

  val uType = io.type_decode_together_i(8)
  val jal = io.type_decode_together_i(7)
  val jalr = io.type_decode_together_i(6)
  val bType = io.type_decode_together_i(5)
  val lType = io.type_decode_together_i(4)
  val iType = io.type_decode_together_i(3)
  val sType = io.type_decode_together_i(2)
  val rType = io.type_decode_together_i(1)
  val other = io.type_decode_together_i(0)

  val lui = uType && io.inst_i(5)
  val auipc = uType && !io.inst_i(5)

  val iChoose30OrNot = iType && (funct3 === "b101".U)
  val rChoose30OrNot = rType && ((funct3 === "b101".U) || (funct3 === "b000".U))

  val aluA = Mux(bType || lType || sType || iType || rType, io.reg_rdata1_i, io.inst_addr_i)
  val aluB = Mux(bType || rType, io.reg_rdata2_i, io.imm_i)

  val bCtrl = Mux(funct3(2), Cat(0.U(2.W), funct3(2, 1)), 8.U(4.W))
  val iCtrl = Cat(iChoose30OrNot && io.inst_i(30), funct3)
  val rCtrl = Cat(rChoose30OrNot && io.inst_i(30), funct3)
  val aluCtrl = Wire(UInt(5.W))
  aluCtrl := 0.U
  when(bType) {
    aluCtrl := Cat(0.U(1.W), bCtrl)
  }.elsewhen(iType) {
    aluCtrl := Cat(0.U(1.W), iCtrl)
  }.elsewhen(rType) {
    aluCtrl := Cat(0.U(1.W), rCtrl)
  }

  val uALU = Module(new ALU)
  uALU.io.a := aluA
  uALU.io.b := aluB
  uALU.io.ctrl := aluCtrl
  uALU.io.enable := !(lui || other)

  io.inst_funct3_o := Mux(lType || sType, funct3, 0.U)
  io.inst_rd_o := Mux(uType || jal || jalr || lType || iType || rType, rd, 0.U)
  io.data_o := Mux(lui, io.imm_i, Mux(auipc || jal || jalr || lType || sType || iType || rType, uALU.io.result, 0.U))
  io.reg_rdata2_o := Mux(sType, io.reg_rdata2_i, 0.U)
  io.alu_zero_o := uALU.io.zero
  io.type_decode_together_o := io.type_decode_together_i
}
