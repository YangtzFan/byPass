package mycpu

import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage.ChiselStage

// ============================================================================
// GenerateVerilog —— Chisel 编译入口
// ============================================================================
// 运行此 App 会将 SoC_Top 编译为 SystemVerilog，输出到 build/rtl/ 目录
// IROM 指令内容不再在编译期加载，改为仿真运行时由 difftest 框架动态加载
// ============================================================================
object GenerateVerilog extends App {
  // FIRRTL 编译选项：输出格式 SystemVerilog，拆分文件，目标目录 build/rtl
  private val firrtlOpts =
    if (args.nonEmpty) args
    else
      Array("--target", "systemverilog", "--split-verilog", "-td", "build/rtl")

  // 执行 Chisel → FIRRTL → SystemVerilog 编译流程
  (new ChiselStage).execute(
    firrtlOpts,
    Seq(ChiselGeneratorAnnotation(() => new SoC_Top()))
  )
}
