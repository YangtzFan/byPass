package mycpu.device

import chisel3._
import chisel3.util._

object utils {
  def signExtend(value: UInt, targetWidth: Int = 32): UInt = { // 将一个较短位宽的无符号值按“有符号数”规则扩展
    val sourceWidth = value.getWidth
    require(sourceWidth >= 1 && sourceWidth <= 32)
    Cat(Fill(targetWidth - sourceWidth, value(sourceWidth - 1)), value)
  }
  def unsignExtend(value: UInt, targetWidth: Int = 32): UInt = {
    val sourceWidth = value.getWidth
    require(sourceWidth >= 1 && sourceWidth <= 32)
    Cat(0.U((targetWidth - sourceWidth).W), value)
  }
}
