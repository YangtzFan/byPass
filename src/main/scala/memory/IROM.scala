package mycpu.memory

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

// ============================================================================
// IROM —— 指令 ROM（单端口，128 位宽读取）
// ============================================================================
// 每次读取 128 位 = 4 条 32 位指令，供 Fetch 阶段 4 路取指使用
// 地址输入为 128 位字地址（即 PC[17:4]），对应 16KB 地址空间
// 存储内容在 Chisel 编译期由 .bin 文件转换为 .hex 内联加载
// ============================================================================
class IROM extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(14.W))       // 128 位字地址（PC[17:4]）
    val spo = Output(UInt(128.W))     // 128 位输出：4 条指令打包
  })

  // 2^14 = 16384 个 128 位条目 → 总容量 256KB
  val mem = Mem(1 << 14, UInt(128.W))
  loadMemoryFromFileInline(mem, IROM.ensureHexFileFromBin())

  io.spo := mem(io.a)   // 组合读，地址到数据无需时钟边沿
}

// ============================================================================
// IROM 伴生对象 —— .bin → .hex 转换工具
// ============================================================================
// 在 Chisel 编译期执行：
//   1. 从 cdp-tests/bin/<name>.bin 读取二进制指令
//   2. 将每 4 条指令（16 字节）打包为一行 128 位十六进制
//   3. 输出到 build/rtl/<name>.hex，供 loadMemoryFromFileInline 使用
//
// 打包格式（每行 32 个十六进制字符）：
//   hex 字符串从高位到低位排列：word3 word2 word1 word0
//   对应 128 位数据的 [127:96] [95:64] [63:32] [31:0]
// ============================================================================
object IROM {
  private def ensureHexFileFromBin(): String = {
    // 从系统属性获取测试用例名（默认 "andi"）
    val binName = Option(System.getProperty("irom.bin.name")).map(_.trim).filter(_.nonEmpty).getOrElse("andi")
    val binPath = Paths.get("cdp-tests/bin", s"$binName.bin")
    val hexPath = Paths.get("build", "rtl", s"$binName.hex")

    if (!Files.exists(binPath)) throw new IllegalArgumentException(s"IROM init file not found: ${binPath.toAbsolutePath}")

    Files.createDirectories(hexPath.getParent)

    val bytes = Files.readAllBytes(binPath)
    require(bytes.length % 4 == 0, s"${binPath.toAbsolutePath} size must be a multiple of 4 bytes")

    // 补齐到 16 字节（128 位 = 4 条指令）的整数倍
    val paddedLen = ((bytes.length + 15) / 16) * 16
    val paddedBytes = bytes ++ Array.fill(paddedLen - bytes.length)(0.toByte)

    val sb = new StringBuilder(paddedLen / 16 * 33)
    for (chunk <- paddedBytes.grouped(16)) {
      // 将 4 个小端序 32 位字打包成一行 128 位十六进制
      // 每个字的字节序：byte[0]在最低位（little-endian）
      // hex 输出按高位到低位排列：word3 word2 word1 word0
      val words = chunk.grouped(4).map { w =>
        ((w(0) & 0xff).toLong | ((w(1) & 0xff).toLong << 8) |
         ((w(2) & 0xff).toLong << 16) | ((w(3) & 0xff).toLong << 24)) & 0xffffffffL
      }.toArray
      sb.append(f"${words(3)}%08x${words(2)}%08x${words(1)}%08x${words(0)}%08x").append("\n")
    }

    Files.write(hexPath, sb.toString.getBytes(StandardCharsets.US_ASCII))
    hexPath.toAbsolutePath.toString
  }
}
