import chisel3._
import chisel3.util._
import os.Source.WritableSource
import utils._

/**
  * SE = Sign Extension / Immediate Generation
  *
  * 支持的立即数类型：
  * - U-type  : lui / auipc
  * - B-type  : 分支偏移立即数
  * - S-type  : Store 类立即数
  * - L-type  : Load 类指令的 I-type 立即数
  * - I-type  : 算术/逻辑立即数
  * - JAL     : 这里特殊处理为输出 4（用于写回 PC + 4）
  * - JALR    : 这里特殊处理为输出 4（用于写回 PC + 4）
  *
  * 注意：JAL/JALR 并没有真正生成跳转偏移立即数，而是直接输出 4，用于 rd <- PC + 4 的写回数据。
  * JAL/JALR 的跳转目标地址生成逻辑应当在别处完成。
  */

class SE extends Module {
  val io = IO(new Bundle {
    val type_decode_together = Input(UInt(9.W))
    val imm12  = Input(UInt(12.W))
    val imm20  = Input(UInt(20.W))
    val funct7 = Input(UInt(7.W))
    val funct3 = Input(UInt(3.W)) // 当前版本未使用，保留接口
    val rd     = Input(UInt(5.W))
    val imm_o  = Output(UInt(32.W))
  })

  val U_type = io.type_decode_together(8)
  val JAL = io.type_decode_together(7)
  val JALR = io.type_decode_together(6)
  val B_type = io.type_decode_together(5)
  val L_type = io.type_decode_together(4)
  val I_type = io.type_decode_together(3)
  val S_type = io.type_decode_together(2)

  private val splitImm12 = Cat(io.funct7, io.rd) // S/B 型指令的 12 位原始立即数字段来自 inst[31:25] ++ inst[11:7]
  private def iImm: UInt = signExtend(io.imm12) // JALR / I-type / L-type: 直接对 imm12 做符号扩展
  private def sImm: UInt = signExtend(splitImm12) // S-type: 对拼出来的 12 位立即数做符号扩展
  private def bImm: UInt = signExtend(Cat( // B-type: 原始字段 splitImm12 = inst[31:25] ++ inst[11:7] 需要拼接重组
    splitImm12(11),    // imm[12] = inst[31]
    splitImm12(0),     // imm[11] = inst[7]
    splitImm12(10, 5), // imm[10:5] = inst[30:25]
    splitImm12(4, 1),  // imm[4:1] = inst[11:8]
    0.U(1.W)           // imm[0] = 0
  ))
  private def uImm: UInt = Cat(io.imm20, 0.U(12.W)) // U-type: 高 20 位直接放到 [31:12]，低 12 位补 0
  private def jImm: UInt = signExtend(Cat(
    io.imm20(19),      // inst[31] -> imm[20]
    io.imm20(7, 0),    // inst[19:12] -> imm[19:12]
    io.imm20(8),       // inst[20] -> imm[11]
    io.imm20(18, 9),   // inst[30:21] -> imm[10:1]
    0.U(1.W)           // imm[0] = 0
  ))

  // 按优先级选择输出立即数
  io.imm_o := PriorityMux(Seq(
    U_type             -> uImm,
    JAL                -> jImm,
    JALR               -> iImm,
    B_type             -> bImm,
    (L_type || I_type) -> iImm,
    S_type             -> sImm,
    true.B             -> 0.U(32.W)
  ))
}
