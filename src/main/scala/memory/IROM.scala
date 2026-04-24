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
// ============================================================================
class IROM extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(14.W))       // 128 位字地址（PC[17:4]）
    val spo = Output(UInt(128.W))     // 128 位输出：4 条指令打包
  })

  // 2^14 = 16384 个 128 位条目 → 总容量 256KB
  val mem = Mem(1 << 14, UInt(128.W))

  // 使用占位 hex 文件进行编译期初始化（防止 FIRRTL 优化掉内存）
  // difftest 框架会在 RTL 后处理中将此路径替换为实际的 irom.hex 路径
  loadMemoryFromFileInline(mem, IROM.ensurePlaceholderHex())

  io.spo := mem(io.a)   // 组合读，地址到数据无需时钟边沿
}

// ============================================================================
// IROM 伴生对象 —— 占位 hex 文件生成
// ============================================================================
// 在 Chisel 编译期创建占位 hex 文件，防止 FIRRTL 优化掉 IROM 内存。实际用例的加载由 difftest 框架负责。
// ============================================================================
object IROM {
  def ensurePlaceholderHex(): String = {
    val hexPath = Paths.get("build", "rtl", "irom_placeholder.hex")
    Files.createDirectories(hexPath.getParent)
    // 创建一个只包含一行全零的占位 hex 文件（128 位 = 32 个十六进制字符）
    Files.write(hexPath, "00000000000000000000000000000000\n".getBytes(StandardCharsets.US_ASCII))
    hexPath.toAbsolutePath.toString
  }
}
