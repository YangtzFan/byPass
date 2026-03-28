package mycpu.dataLane

import chisel3._
import chisel3.util._

class MEM extends Module {
  val in = IO(Flipped(Decoupled(new EX_MEM_Payload)))
  val out = IO(Decoupled(new MEM_WB_Payload))
  val io = IO(new Bundle {
    val ram_addr_o = Output(UInt(32.W))
    val ram_wdata_o = Output(UInt(32.W))
    val ram_wen_o = Output(Bool())
    val ram_mask_o = Output(UInt(3.W))
    val ram_rdata_i = Input(UInt(32.W))
  })

  val uType = in.bits.type_decode_together(8)
  val jal = in.bits.type_decode_together(7)
  val jalr = in.bits.type_decode_together(6)
  val lType = in.bits.type_decode_together(4)
  val iType = in.bits.type_decode_together(3)
  val sType = in.bits.type_decode_together(2)
  val rType = in.bits.type_decode_together(1)

  io.ram_addr_o := Mux(lType || sType, in.bits.data, 0.U)
  io.ram_mask_o := Mux(lType || sType, in.bits.inst_funct3, 0.U)
  io.ram_wen_o := sType
  io.ram_wdata_o := Mux(sType, in.bits.reg_rdata2, 0.U)

  out.bits.inst_addr := in.bits.inst_addr
  out.bits.inst_rd := Mux(uType || jal || jalr || lType || iType || rType, in.bits.inst_rd, 0.U)
  out.bits.data := MuxCase(0.U, Seq(
    (uType || jal || jalr || iType || rType) -> in.bits.data,
    lType -> io.ram_rdata_i
  ))

  out.bits.type_decode_together := in.bits.type_decode_together

  in.ready := out.ready
  out.valid := in.valid
}
