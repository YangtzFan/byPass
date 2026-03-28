package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig
import mycpu.device.SE

class ID extends Module {
  val in = IO(Flipped(Decoupled(new IF_ID_Payload)))
  val out = IO(Decoupled(new ID_EX_Payload))
  val io = IO(new Bundle {
    val reg_raddr1_o = Output(UInt(5.W))
    val reg_raddr2_o = Output(UInt(5.W))
    val reg_rdata1_i = Input(UInt(32.W))
    val reg_rdata2_i = Input(UInt(32.W))
    val use_rs1_o = Output(Bool())
    val use_rs2_o = Output(Bool())
    val predict_jump = Output(Bool())       // 预测跳转使能
    val predict_target = Output(UInt(32.W)) // 预测跳转目标地址
  })

  // BHT 动态预测输入端口（仅当 CPUConfig.useBHT 时生成）
  val bht_predict_taken = Option.when(CPUConfig.useBHT)(IO(Input(Bool())))

  val opcode = in.bits.inst(6, 0)
  val rd = in.bits.inst(11, 7)
  val funct3 = in.bits.inst(14, 12)
  val rs1 = in.bits.inst(19, 15)
  val rs2 = in.bits.inst(24, 20)
  val funct7 = in.bits.inst(31, 25)
  val imm12 = Cat(funct7, rs2)
  val imm20 = Cat(funct7, rs2, rs1, funct3)

  val uType = (opcode === "b0110111".U) || (opcode === "b0010111".U)
  val jal = in.bits.type_decode_together(2)   // IF: Cat(jal[2], jalr[1], bType[0])
  val jalr = in.bits.type_decode_together(1)
  val bType = in.bits.type_decode_together(0)
  val lType = opcode === "b0000011".U
  val iType = opcode === "b0010011".U
  val sType = opcode === "b0100011".U
  val rType = opcode === "b0110011".U
  val other = (opcode === "b0001111".U) || (opcode === "b1110011".U)

  val type_decode_together = Cat(uType, jal, jalr, bType, lType, iType, sType, rType, other)

  val uSE = Module(new SE)
  uSE.io.type_decode_together := out.bits.type_decode_together
  uSE.io.imm12 := imm12
  uSE.io.imm20 := imm20
  uSE.io.funct7 := funct7
  uSE.io.funct3 := funct3
  uSE.io.rd := rd

  val use_rs1 = jalr || bType || iType || sType || rType || lType
  val use_rs2 = bType || sType || rType
  io.reg_raddr1_o := Mux(use_rs1, rs1, 0.U)
  io.reg_raddr2_o := Mux(use_rs2, rs2, 0.U)
  io.use_rs1_o := use_rs1
  io.use_rs2_o := use_rs2

  out.bits.inst_addr := in.bits.inst_addr
  out.bits.inst := in.bits.inst
  out.bits.reg_rdata1 := io.reg_rdata1_i
  out.bits.reg_rdata2 := io.reg_rdata2_i
  out.bits.imm := uSE.io.imm_o
  out.bits.type_decode_together := type_decode_together

  // ---- 分支预测（根据 CPUConfig 选择性生成）----
  val jal_target = in.bits.inst_addr + uSE.io.imm_o // JAL 跳转目标 = PC + J_imm
  val b_target = in.bits.inst_addr + uSE.io.imm_o   // B-type 跳转目标 = PC + B_imm

  // B-type 预测策略：
  // - DynamicBHT: 使用 2-bit 饱和计数器动态预测
  // - StaticBTFN: 向后跳转预测 taken，向前预测 not-taken
  val b_predict_taken = if (CPUConfig.useBHT) {
    bType && bht_predict_taken.get  // BHT 动态预测
  } else {
    bType && uSE.io.imm_o(31)      // BTFN: B_imm 符号位=1 → 向后跳转
  }
  val predict_taken = jal || b_predict_taken // JAL 必定跳转
  io.predict_jump := predict_taken
  io.predict_target := Mux(jal, jal_target, b_target)
  out.bits.predicted_taken := predict_taken

  in.ready := out.ready
  out.valid := in.valid
}
