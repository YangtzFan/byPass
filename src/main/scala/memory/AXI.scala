package mycpu.memory

import chisel3._
import chisel3.util._

// ============================================================================
// AXI —— 本工程使用的最小化 AXI4 接口（参考 src/main/scala/temp/axi.scala）
// ============================================================================
// 本工程当前只对接“单一外部 DRAM 从设备”，且后端模块（AXIStoreQueue）保证
// 任意时刻最多只有一笔事务在飞，因此不需要支持以下 AXI 字段：
//   - len/size/burst（仅做单拍、4 字节、FIXED 突发）
//   - lock/cache/qos/region/user/last/prot
// 这些字段被裁掉后，AXI 通道只保留事务必需的：地址、数据、字节使能、
// 事务 ID、响应码（resp）。
//
// 命名约定：以下 Bundle 都从“主设备发起方向”定义；
// 从设备一侧使用 `Flipped(new AXIBundle(p))` 即可。
// ============================================================================

case class AXIParams(
  addrBits: Int = 32,
  dataBits: Int = 32,
  idBits: Int   = 4
) {
  require(dataBits % 8 == 0, "AXI dataBits 必须为 8 的整数倍")
  val strbBits: Int = dataBits / 8
}

// ---- AW / AR 通道 ----
// 简化后只保留事务最关键的字段：事务 ID 与字节地址。
class AXIAddr(val p: AXIParams) extends Bundle {
  val id   = UInt(p.idBits.W)
  val addr = UInt(p.addrBits.W)
}

// ---- W 通道（写数据 + 字节使能）----
class AXIW(val p: AXIParams) extends Bundle {
  val data = UInt(p.dataBits.W)
  val strb = UInt(p.strbBits.W)
}

// ---- B 通道（写响应）----
class AXIB(val p: AXIParams) extends Bundle {
  val id   = UInt(p.idBits.W)
  val resp = UInt(2.W) // 00=OKAY, 10=SLVERR, 11=DECERR
}

// ---- R 通道（读响应）----
class AXIR(val p: AXIParams) extends Bundle {
  val id   = UInt(p.idBits.W)
  val data = UInt(p.dataBits.W)
  val resp = UInt(2.W)
}

// ============================================================================
// AXIBundle —— 主设备视角的完整 5 通道接口
// ============================================================================
// AW、W、AR：主设备 -> 从设备（Decoupled，valid 由主驱动）
// B、R：从设备 -> 主设备（Flipped Decoupled，valid 由从驱动）
// ============================================================================
class AXIBundle(val p: AXIParams = AXIParams()) extends Bundle {
  val aw = Decoupled(new AXIAddr(p))
  val w  = Decoupled(new AXIW(p))
  val b  = Flipped(Decoupled(new AXIB(p)))
  val ar = Decoupled(new AXIAddr(p))
  val r  = Flipped(Decoupled(new AXIR(p)))
}

object AXIResp {
  def OKAY: UInt = "b00".U(2.W)
}
