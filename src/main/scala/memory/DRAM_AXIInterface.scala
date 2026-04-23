package mycpu.memory

import chisel3._
import chisel3.util._

// ============================================================================
// DRAM_AXIInterface —— DRAM 的 AXI 从设备适配器
// ============================================================================
// 取代旧的 `dram_driver`。对外暴露 AXI 从设备接口，对内驱动 32 位字宽 DRAM。
// 设计假设：
//   1. 上游主设备（AXIStoreQueue）保证任意时刻最多只有一笔事务在飞，因此本
//      模块内部只需一个简单 3 状态机（Idle / WriteResp / ReadResp）。
//   2. 写事务为单拍（无突发），AW 与 W 必须同拍达成握手。
//   3. 读事务为单拍，AR 握手当拍即可由 DRAM 组合读取数据并锁存返回。
//   4. 字节使能（wstrb）由本模块通过“读出旧字 -> 按字节合并 -> 写回新字”实现，
//      与原 dram_driver 行为完全一致。
// 不支持的字段：len、size、burst、lock、cache、qos、region、user、last、prot
// 这些字段在 AXIBundle 中已被裁掉。
//
// 同拍若 AW/W 与 AR 都有效，本模块按“写优先”策略仲裁，避免读写穿插带来的
// 数据竞争——上游也只会发起其中一种事务，故该仲裁选择只是一道防御性兜底。
// ============================================================================
class DRAM_AXIInterface extends Module {
  // 默认 AXIParams（32 位地址、32 位数据、4 位 ID），与 AXIStoreQueue 对齐。
  val axi = IO(Flipped(new AXIBundle()))

  // ---- 内部 DRAM 实例 ----
  val memDram  = Module(new DRAM)
  val wordAddr = Wire(UInt(16.W)) // DRAM 字寻址（16 位 = 64K 字 = 256KB）
  wordAddr := 0.U
  memDram.io.a := wordAddr
  // DRAM 写默认关闭，仅在写状态机中拉起。
  memDram.io.we := false.B
  memDram.io.d  := 0.U

  // ---- 状态机 ----
  val sIdle :: sWriteResp :: sReadResp :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // 锁存的事务上下文（事务 ID 与读出数据，用于在响应通道还原）
  val savedId    = RegInit(0.U(axi.p.idBits.W))
  val savedRdata = RegInit(0.U(axi.p.dataBits.W))

  // ---- 默认握手取消（在状态机中按需拉高）----
  axi.aw.ready := false.B
  axi.w.ready  := false.B
  axi.ar.ready := false.B
  axi.b.valid  := false.B
  axi.b.bits.id   := savedId
  axi.b.bits.resp := AXIResp.OKAY
  axi.r.valid     := false.B
  axi.r.bits.id   := savedId
  axi.r.bits.data := savedRdata
  axi.r.bits.resp := AXIResp.OKAY

  switch(state) {
    is(sIdle) {
      // 写优先：只有当 AW 与 W 同时有效，才视为一次完整写事务。
      val writeFire = axi.aw.valid && axi.w.valid
      val readFire  = axi.ar.valid && !writeFire

      when(writeFire) {
        axi.aw.ready := true.B
        axi.w.ready  := true.B
        // 字节地址转字地址：AXI addr 的低 2 位为字节偏移，DRAM 按字寻址。
        val byteAddr = axi.aw.bits.addr
        val wAddr    = byteAddr(17, 2)
        wordAddr     := wAddr
        // 先组合读取该字旧值，再按 wstrb 逐字节合并写入数据。
        val oldWord = memDram.io.spo
        val mergedBytes = Wire(Vec(4, UInt(8.W)))
        for (b <- 0 until 4) {
          mergedBytes(b) := Mux(
            axi.w.bits.strb(b),
            axi.w.bits.data(b * 8 + 7, b * 8),
            oldWord(b * 8 + 7, b * 8)
          )
        }
        memDram.io.we := true.B
        memDram.io.d  := Cat(mergedBytes(3), mergedBytes(2), mergedBytes(1), mergedBytes(0))
        savedId := axi.aw.bits.id
        state   := sWriteResp
      }.elsewhen(readFire) {
        axi.ar.ready := true.B
        val byteAddr = axi.ar.bits.addr
        wordAddr     := byteAddr(17, 2)
        // DRAM 当拍组合输出读数据；锁存到下一拍通过 R 通道返回。
        savedRdata := memDram.io.spo
        savedId    := axi.ar.bits.id
        state      := sReadResp
      }
    }

    is(sWriteResp) {
      axi.b.valid := true.B
      when(axi.b.ready) { state := sIdle }
    }

    is(sReadResp) {
      axi.r.valid := true.B
      when(axi.r.ready) { state := sIdle }
    }
  }
}
