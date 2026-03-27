import chisel3._

class SoC_Top extends Module {
  val io = IO(new Bundle {
    val debug_wb_have_inst = Output(Bool())
    val debug_wb_pc = Output(UInt(32.W))
    val debug_wb_ena = Output(Bool())
    val debug_wb_reg = Output(UInt(5.W))
    val debug_wb_value = Output(UInt(32.W))
  })

  val instAddr = Wire(UInt(32.W))
  val instData = Wire(UInt(32.W))

  val dataAddr = Wire(UInt(32.W))
  val dataWen = Wire(Bool())
  val dataMask = Wire(UInt(3.W))
  val dataW = Wire(UInt(32.W))
  val dataR = Wire(UInt(32.W))

  val coreCpu = Module(new myCPU)

  instAddr := coreCpu.io.inst_addr_o
  coreCpu.io.inst_i := instData

  dataAddr := coreCpu.io.ram_addr_o
  dataWen := coreCpu.io.ram_wen_o
  dataMask := coreCpu.io.ram_mask_o
  dataW := coreCpu.io.ram_wdata_o
  coreCpu.io.ram_rdata_i := dataR

  io.debug_wb_have_inst := coreCpu.io.wb_inst_bubble_ifornot
  io.debug_wb_pc := coreCpu.io.wb_inst_addr
  io.debug_wb_ena := coreCpu.io.wb_reg_wen
  io.debug_wb_reg := coreCpu.io.wb_reg_waddr
  io.debug_wb_value := coreCpu.io.wb_reg_wdata

  val memIrom = Module(new IROM)
  memIrom.io.a := instAddr(17, 2)
  instData := memIrom.io.spo

  val uDramDriver = Module(new dram_driver)
  uDramDriver.io.perip_addr := dataAddr(17, 0)
  uDramDriver.io.perip_wdata := dataW
  uDramDriver.io.perip_mask := dataMask
  uDramDriver.io.dram_wen := dataWen
  dataR := uDramDriver.io.perip_rdata
}
