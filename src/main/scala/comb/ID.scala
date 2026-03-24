import chisel3._
import chisel3.util._
import chisel3.util._

class ID extends Module {
  val io = IO(new Bundle {
    val inst_addr_i = Input(UInt(32.W))
    val inst_i = Input(UInt(32.W))
    val reg_raddr1_o = Output(UInt(5.W))
    val reg_raddr2_o = Output(UInt(5.W))
    val imm_o = Output(UInt(32.W))
    val type_decode_together_o = Output(UInt(9.W))
  })

  val opcode = io.inst_i(6, 0)
  val rd = io.inst_i(11, 7)
  val funct3 = io.inst_i(14, 12)
  val rs1 = io.inst_i(19, 15)
  val rs2 = io.inst_i(24, 20)
  val funct7 = io.inst_i(31, 25)
  val imm12 = Cat(funct7, rs2)
  val imm20 = Cat(funct7, rs2, rs1, funct3)

  val uType = (opcode === "b0110111".U) || (opcode === "b0010111".U)
  val jal = opcode === "b1101111".U
  val jalr = opcode === "b1100111".U
  val bType = opcode === "b1100011".U
  val lType = opcode === "b0000011".U
  val iType = opcode === "b0010011".U
  val sType = opcode === "b0100011".U
  val rType = opcode === "b0110011".U
  val other = (opcode === "b0001111".U) || (opcode === "b1110011".U)

  io.type_decode_together_o := Cat(uType, jal, jalr, bType, lType, iType, sType, rType, other)

  val uSE = Module(new SE)
  uSE.io.U_type := uType
  uSE.io.JAL := jal
  uSE.io.JALR := jalr
  uSE.io.B_type := bType
  uSE.io.L_type := lType
  uSE.io.I_type := iType
  uSE.io.S_type := sType
  uSE.io.imm12 := imm12
  uSE.io.imm20 := imm20
  uSE.io.funct7 := funct7
  uSE.io.funct3 := funct3
  uSE.io.rd := rd
  io.imm_o := uSE.io.imm_o

  val sendRs1ToRaddr1 = jalr || bType || iType || sType || rType || lType
  val sendRs2ToRaddr2 = bType || sType || rType
  io.reg_raddr1_o := Mux(sendRs1ToRaddr1, rs1, 0.U)
  io.reg_raddr2_o := Mux(sendRs2ToRaddr2, rs2, 0.U)
}
