import chisel3._
import chisel3.util._
import chisel3.util._

class SE extends Module {
  val io = IO(new Bundle {
    val U_type = Input(Bool())
    val JAL = Input(Bool())
    val JALR = Input(Bool())
    val B_type = Input(Bool())
    val L_type = Input(Bool())
    val I_type = Input(Bool())
    val S_type = Input(Bool())
    val imm12 = Input(UInt(12.W))
    val imm20 = Input(UInt(20.W))
    val funct7 = Input(UInt(7.W))
    val funct3 = Input(UInt(3.W))
    val rd = Input(UInt(5.W))
    val imm_o = Output(UInt(32.W))
  })

  val input12Together = io.L_type || io.I_type
  val input12Split = io.B_type || io.S_type
  val input12 = input12Together || input12Split
  val input20 = io.U_type

  val input12data = Wire(UInt(12.W))
  input12data := Mux(input12Together, io.imm12, Cat(io.funct7, io.rd))

  val output12data = Wire(UInt(32.W))
  output12data := Mux(
    io.B_type,
    Cat(Fill(20, input12data(11)), input12data(0), input12data(10, 1), 0.U(1.W)),
    Cat(Fill(21, input12data(11)), input12data(10, 0))
  )

  val output20data = Cat(io.imm20, 0.U(12.W))

  io.imm_o := Mux(
    input20,
    output20data,
    Mux(input12, output12data, Mux(io.JAL || io.JALR, 4.U(32.W), 0.U(32.W)))
  )
}
