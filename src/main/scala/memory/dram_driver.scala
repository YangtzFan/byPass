import chisel3._
import chisel3.util._

class dram_driver extends Module {
  val io = IO(new Bundle {
    val perip_addr = Input(UInt(18.W))
    val perip_wdata = Input(UInt(32.W))
    val perip_mask = Input(UInt(3.W))
    val dram_wen = Input(Bool())
    val perip_rdata = Output(UInt(32.W))
  })

  val dramAddr = io.perip_addr(17, 2)
  val offset = io.perip_addr(1, 0)

  val memDram = Module(new DRAM)
  memDram.io.a := dramAddr
  memDram.io.we := io.dram_wen

  val dramRdataRaw = memDram.io.spo

  val lbLbuSb = io.perip_mask(1, 0) === "b00".U
  val lhLhuSh = io.perip_mask(1, 0) === "b01".U
  val lb = lbLbuSb && !io.perip_mask(2)
  val lbu = lbLbuSb && io.perip_mask(2)
  val lh = lhLhuSh && !io.perip_mask(2)
  val lhu = lhLhuSh && io.perip_mask(2)
  val lwSw = io.perip_mask(1, 0) === "b10".U

  val offset00 = offset === "b00".U
  val offset01 = offset === "b01".U
  val offset10 = offset === "b10".U
  val offset11 = offset === "b11".U

  val readdata8 = Mux1H(
    Seq(
      offset00 -> dramRdataRaw(7, 0),
      offset01 -> dramRdataRaw(15, 8),
      offset10 -> dramRdataRaw(23, 16),
      offset11 -> dramRdataRaw(31, 24)
    )
  )
  val extension24 = Mux(lb, Fill(24, readdata8(7)), Mux(lbu, 0.U(24.W), 0.U(24.W)))

  val readdata16 = Mux(offset(1), dramRdataRaw(31, 16), dramRdataRaw(15, 0))
  val extension16 = Mux(lh, Fill(16, readdata16(15)), Mux(lhu, 0.U(16.W), 0.U(16.W)))

  val dout = Mux(lbLbuSb, Cat(extension24, readdata8), Mux(lhLhuSh, Cat(extension16, readdata16), Mux(lwSw, dramRdataRaw, 0.U)))
  io.perip_rdata := dout

  val dramDataSb = Mux1H(
    Seq(
      offset00 -> Cat(dramRdataRaw(31, 8), io.perip_wdata(7, 0)),
      offset01 -> Cat(dramRdataRaw(31, 16), io.perip_wdata(7, 0), dramRdataRaw(7, 0)),
      offset10 -> Cat(dramRdataRaw(31, 24), io.perip_wdata(7, 0), dramRdataRaw(15, 0)),
      offset11 -> Cat(io.perip_wdata(7, 0), dramRdataRaw(23, 0))
    )
  )

  val dramData = Mux(
    lwSw,
    io.perip_wdata,
    Mux(
      lhLhuSh,
      Mux(offset(1), Cat(io.perip_wdata(15, 0), dramRdataRaw(15, 0)), Cat(dramRdataRaw(31, 16), io.perip_wdata(15, 0))),
      dramDataSb
    )
  )

  memDram.io.d := dramData
}
