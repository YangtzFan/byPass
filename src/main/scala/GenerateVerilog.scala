package mycpu

import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage.ChiselStage

object GenerateVerilog extends App {
  private val firrtlOpts =
    if (args.nonEmpty) args
    else
      Array("--target", "systemverilog", "--split-verilog", "-td", "build/rtl")

  private val selectedBin =
    Option(System.getenv("IROM_BIN"))
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse("and")

  System.setProperty("irom.bin.name", selectedBin)

  (new ChiselStage).execute(
    firrtlOpts,
    Seq(ChiselGeneratorAnnotation(() => new SoC_Top()))
  )
}
