package mycpu.memory

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

class IROM extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(16.W))
    val spo = Output(UInt(32.W))
  })

  val mem = Mem(1 << 16, UInt(32.W))
  loadMemoryFromFileInline(mem, IROM.ensureHexFileFromBin())
  io.spo := mem(io.a)
}

object IROM {
  private def ensureHexFileFromBin(): String = {
    val binName = Option(System.getProperty("irom.bin.name")).map(_.trim).filter(_.nonEmpty).getOrElse("andi")
    val binPath = Paths.get("cdp-tests/bin", s"$binName.bin")
    val hexPath = Paths.get("build", "rtl", s"$binName.hex")

    if (!Files.exists(binPath)) throw new IllegalArgumentException(s"IROM init file not found: ${binPath.toAbsolutePath}")

    Files.createDirectories(hexPath.getParent)

    val bytes = Files.readAllBytes(binPath)
    require(bytes.length % 4 == 0, s"${binPath.toAbsolutePath} size must be a multiple of 4 bytes")

    val sb = new StringBuilder(bytes.length / 4 * 9)
    for (w <- bytes.grouped(4)) {
      val word = (w(0) & 0xff) | ((w(1) & 0xff) << 8) | ((w(2) & 0xff) << 16) | ((w(3) & 0xff) << 24)
      sb.append(f"$word%08x").append("\n")
    }

    Files.write(hexPath, sb.toString.getBytes(StandardCharsets.US_ASCII))
    hexPath.toAbsolutePath.toString
  }
}
