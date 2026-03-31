package mycpu

import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage.ChiselStage

// ============================================================================
// GenerateVerilog —— Chisel 编译入口
// ============================================================================
// 运行此 App 会将 SoC_Top 编译为 SystemVerilog，输出到 build/rtl/ 目录
// 可通过环境变量 IROM_BIN 指定要加载哪个测试程序的二进制文件，例如：
//   IROM_BIN=add mill -i byPass.runMain mycpu.GenerateVerilog
// 若未指定，默认使用 "and" 测试程序
// ============================================================================
object GenerateVerilog extends App {
  // FIRRTL 编译选项：输出格式 SystemVerilog，拆分文件，目标目录 build/rtl
  private val firrtlOpts =
    if (args.nonEmpty) args
    else
      Array("--target", "systemverilog", "--split-verilog", "-td", "build/rtl")

  // 从环境变量 IROM_BIN 获取测试程序名（如 "add"、"beq" 等）
  private val selectedBin =
    Option(System.getenv("IROM_BIN"))
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse("and")

  // 设置 JVM 系统属性，IROM 模块会在编译时读取这个属性来加载对应的 .bin 文件
  System.setProperty("irom.bin.name", selectedBin)

  // 执行 Chisel → FIRRTL → SystemVerilog 编译流程
  (new ChiselStage).execute(
    firrtlOpts,
    Seq(ChiselGeneratorAnnotation(() => new SoC_Top()))
  )
}
