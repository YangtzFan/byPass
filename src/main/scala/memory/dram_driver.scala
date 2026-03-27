import chisel3._
import chisel3.util._
import utils._

class dram_driver extends Module {
  val io = IO(new Bundle {
    val perip_addr  = Input(UInt(18.W))
    val perip_wdata = Input(UInt(32.W))
    val perip_mask  = Input(UInt(3.W))
    val dram_wen    = Input(Bool())
    val perip_rdata = Output(UInt(32.W))
  })

  // ------------------------------
  // 基础地址拆分
  // ------------------------------
  val wordAddr = io.perip_addr(17, 2) // 按字寻址
  val byteOff  = io.perip_addr(1, 0)  // 字内字节偏移

  val isByte     = io.perip_mask(1, 0) === "b00".U
  val isHalf     = io.perip_mask(1, 0) === "b01".U
  val isWord     = io.perip_mask(1, 0) === "b10".U
  val isUnsigned = io.perip_mask(2) // 仅对读有效

  // ------------------------------
  // 小工具函数
  // ------------------------------
  private def extend(x: UInt, unsigned: Bool): UInt = Mux(unsigned, unsignExtend(x), signExtend(x)) 
  private def toBytes(x: UInt): Vec[UInt] = // 把一个 32 位字拆成 4 个字节，bytes(0) 对应最低字节
    VecInit.tabulate(4)(i => x(8 * i + 7, 8 * i))
  private def fromBytes(bytes: Seq[UInt]): UInt = // 把 4 个字节重新拼成 32 位字，bytes(0) 放最低位
    Cat(bytes.reverse)

  // ------------------------------
  // DRAM 实例
  // ------------------------------
  val memDram = Module(new DRAM)
  memDram.io.a  := wordAddr
  memDram.io.we := io.dram_wen
  val rawData = memDram.io.spo // 读/写 状态下获得到的原始数据

  val dramBytes  = toBytes(rawData)
  val writeBytes = toBytes(io.perip_wdata)

  // ------------------------------
  // 读数据路径
  // ------------------------------
  val selectedByte = dramBytes(byteOff)
  val selectedHalf = Mux(byteOff(1), Cat(dramBytes(3), dramBytes(2)), Cat(dramBytes(1), dramBytes(0)))
  val selectedWord = rawData
  io.perip_rdata := MuxCase(0.U, Seq(
    isByte -> extend(selectedByte, isUnsigned),
    isHalf -> extend(selectedHalf, isUnsigned),
    isWord -> selectedWord
  ))

  // ------------------------------
  // 写数据路径（先默认保持原 DRAM 字节不变，按 SB / SH / SW 覆盖相应字节）
  // ------------------------------
  val mergedBytes = Wire(Vec(4, UInt(8.W)))
  mergedBytes := dramBytes

  when (isByte) {
    mergedBytes(byteOff) := writeBytes(0)
  } .elsewhen (isHalf) {
    when (byteOff(1)) {
      mergedBytes(2) := writeBytes(0)
      mergedBytes(3) := writeBytes(1)
    } .otherwise {
      mergedBytes(0) := writeBytes(0)
      mergedBytes(1) := writeBytes(1)
    }
  } .elsewhen (isWord) {
    mergedBytes := writeBytes
  }

  memDram.io.d := fromBytes(mergedBytes)
}
