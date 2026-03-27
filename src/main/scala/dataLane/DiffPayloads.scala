import chisel3._

class IF_ID_Payload extends Bundle {
  val inst_addr = UInt(32.W)
  val inst = UInt(32.W)
  val type_decode_together = UInt(3.W)
}

class ID_EX_Payload extends Bundle {
  val inst_addr = UInt(32.W)
  val inst = UInt(32.W)
  val reg_rdata1 = UInt(32.W)
  val reg_rdata2 = UInt(32.W)
  val imm = UInt(32.W)
  val type_decode_together = UInt(9.W)
  val predicted_taken = Bool()  // ID 阶段是否预测了 taken（用于 EX 阶段验证）
}

class EX_MEM_Payload extends Bundle{
  val inst_addr = UInt(32.W)
  val inst_funct3 = UInt(3.W)
  val inst_rd = UInt(5.W)
  val data = UInt(32.W)
  val reg_rdata2 = UInt(32.W)
  val type_decode_together = UInt(9.W)
}

class MEM_WB_Payload extends Bundle{
  val inst_addr = UInt(32.W)
  val inst_rd = UInt(5.W)
  val data = UInt(32.W)
  val type_decode_together = UInt(9.W)
}
