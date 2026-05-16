package mycpu.cache

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// ICache —— 4 路组相联只读指令 Cache
// ----------------------------------------------------------------------------
// 设计目标：作为 Fetch 与 IROM 之间的透明只读 Cache，仅服务取指。
//
// 接口（前端 / 后端均为 14-bit chunk-addr + 128-bit data，不走 AXIBundle）：
//   - loadAddr : 来自 Fetch 的 14-bit 取指请求地址（128-bit 字地址 = PC[17:4]）
//   - loadData : 返回给 Fetch 的 128-bit 数据（4 条指令拼接）
//   - memAddr  : 发往后端（IROM）的 14-bit 请求地址
//   - memData  : 从后端返回的 128-bit 数据
//
// 关键设计：
//   1. 单 MSHR 阻塞 miss：任一时刻仅 1 个 miss 在飞，自然保证 loadData 按
//      loadAddr.fire 顺序 FIFO 返回，与 Fetch 单 outstanding 协议天然匹配。
//   2. 14-bit chunk-addr 字段分解：
//        - chunkOff[1:0]    : 行内 chunk 偏移（4 个 chunk/line）
//        - setIdx[idxW+1:2] : set 索引（idxW=4，16 sets）
//        - tag[13:idxW+2]   : tag（8 bits）
//   3. miss 时按序发 4 次后端请求（4 个 chunk），按序写入 dataMem。
//   4. 替换策略：4 路 PLRU 3-bit 树（与 DCache 同款实现）。
//   5. 只读：不实现 dirty / writeback / store；CacheLine = 64 B = 4 chunks。
// ============================================================================
class ICache extends Module {
  require(CPUConfig.cacheAssoc == 4,
    s"ICache 仅支持 4 路组相联，当前 cacheAssoc=${CPUConfig.cacheAssoc}")
  require(CPUConfig.icacheSize > 0,
    s"ICache 仅在 icacheSize>0 时实例化，当前 icacheSize=${CPUConfig.icacheSize}")
  require(CPUConfig.icacheNumSets == 16,
    s"ICache 默认 16 sets（icacheSize=4096 / 64 / 4），当前=${CPUConfig.icacheNumSets}")

  // ---- 对外接口 ----
  val loadAddr = IO(Flipped(Decoupled(UInt(14.W))))  // 来自 Fetch 的取指请求（chunk 地址）
  val loadData = IO(Decoupled(UInt(128.W)))          // 返回 Fetch 的 128-bit 数据
  val memAddr  = IO(Decoupled(UInt(14.W)))           // 发往后端 IROM 的请求
  val memData  = IO(Flipped(Decoupled(UInt(128.W))))  // 从后端 IROM 接收的数据

  // ---- 参数 ----
  private val nSets    = CPUConfig.icacheNumSets    // 16
  private val nWays    = CPUConfig.cacheAssoc       // 4
  private val nChunks  = 4                          // 每行 4 个 128-bit chunk
  private val idxW     = CPUConfig.icacheSetIdxWidth // 4
  private val chunkOffW = log2Ceil(nChunks)          // 2
  private val tagW     = 14 - idxW - chunkOffW      // 8（即 14 - 4 - 2）
  private val wayW     = log2Ceil(nWays)             // 2

  // ---- 14-bit chunk-addr 字段分解 ----
  def addrTag(a: UInt):      UInt = a(13, idxW + chunkOffW)         // 高 8 bit
  def addrSetIdx(a: UInt):   UInt = a(idxW + chunkOffW - 1, chunkOffW) // 中 4 bit
  def addrChunkOff(a: UInt): UInt = a(chunkOffW - 1, 0)              // 低 2 bit

  // ============================================================================
  // 存储阵列（寄存器实现）
  // ============================================================================
  val tags   = Reg(Vec(nSets, Vec(nWays, UInt(tagW.W))))
  val dataMem = Reg(Vec(nSets, Vec(nWays, Vec(nChunks, UInt(128.W)))))
  val valids = RegInit(VecInit(Seq.fill(nSets)(VecInit(Seq.fill(nWays)(false.B)))))

  // ---- PLRU（与 DCache 同款 3-bit 树）----
  val plru = RegInit(VecInit(Seq.fill(nSets)(0.U(3.W))))
  def plruVictim(p: UInt): UInt =
    Mux(p(2) === 0.U,
      Mux(p(1) === 0.U, 0.U(wayW.W), 1.U(wayW.W)),
      Mux(p(0) === 0.U, 2.U(wayW.W), 3.U(wayW.W)))
  def touchPLRU(p: UInt, w: UInt): UInt = {
    val inRight = w(1)
    val leaf    = w(0)
    val newBit2 = ~inRight
    val newBit1 = Mux(inRight === 0.U, ~leaf, p(1))
    val newBit0 = Mux(inRight === 1.U, ~leaf, p(0))
    Cat(newBit2, newBit1, newBit0)
  }

  // ============================================================================
  // 状态机
  // ----------------------------------------------------------------------------
  // sIdle      : 默认状态，命中即返回；miss 时锁存请求并转入 sRefill
  // sRefill    : 发 4 个 chunk 请求 + 收 4 个 chunk 响应；收齐后安装行并转 sFinishMiss
  // sFinishMiss: 把新行对应 chunk 返回给 Fetch，回 sIdle
  // ============================================================================
  val sIdle :: sRefill :: sFinishMiss :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // miss 锁存信息
  val missAddr      = Reg(UInt(14.W))             // 触发 miss 的 14-bit chunk addr
  val missVictimWay = Reg(UInt(wayW.W))           // 替换的 way
  val refillArIdx   = Reg(UInt((chunkOffW + 1).W)) // 0..4：已发 AR 个数
  val refillRIdx    = Reg(UInt((chunkOffW + 1).W)) // 0..4：已收 R  个数

  // ---- 默认信号 ----
  loadAddr.ready    := false.B
  loadData.valid    := false.B
  loadData.bits     := 0.U
  memAddr.valid     := false.B
  memAddr.bits      := 0.U
  memData.ready     := false.B

  // ============================================================================
  // sIdle：命中 / miss 分流
  // ============================================================================
  // 命中检测
  val reqSetIdx   = addrSetIdx(loadAddr.bits)
  val reqTag      = addrTag(loadAddr.bits)
  val reqChunkOff = addrChunkOff(loadAddr.bits)
  val hitVec  = VecInit((0 until nWays).map(w =>
    valids(reqSetIdx)(w) && (tags(reqSetIdx)(w) === reqTag)))
  val hit     = hitVec.asUInt.orR
  val hitWay  = PriorityEncoder(hitVec)

  when(state === sIdle) {
    when(loadAddr.valid) {
      when(hit) {
        // 命中：本拍直接返回数据；与 loadData.fire 同拍 ack loadAddr
        loadData.valid := true.B
        loadData.bits  := dataMem(reqSetIdx)(hitWay)(reqChunkOff)
        when(loadData.fire) {
          loadAddr.ready    := true.B
          plru(reqSetIdx)   := touchPLRU(plru(reqSetIdx), hitWay)
        }
      }.otherwise {
        // miss：锁存请求信息，转 sRefill；不在本拍 ack loadAddr（保持反压）
        missAddr      := loadAddr.bits
        missVictimWay := plruVictim(plru(reqSetIdx))
        refillArIdx   := 0.U
        refillRIdx    := 0.U
        state         := sRefill
      }
    }
  }

  // ============================================================================
  // sRefill：按序发 4 个 chunk AR + 按序收 4 个 chunk R
  // ----------------------------------------------------------------------------
  // chunk 顺序：从 chunk 0 起线性 4 个（一行 4 个 chunk）。
  // 后端地址构造：tag(8) ++ setIdx(4) ++ refillArIdx(2) = 14 bit
  // ============================================================================
  val missSetIdx = addrSetIdx(missAddr)
  val missTag    = addrTag(missAddr)
  val missChunkOff = addrChunkOff(missAddr)

  when(state === sRefill) {
    // ---- 发 AR ----
    when(refillArIdx < nChunks.U) {
      memAddr.valid := true.B
      memAddr.bits  := Cat(missTag, missSetIdx, refillArIdx(chunkOffW - 1, 0))
      when(memAddr.fire) {
        refillArIdx := refillArIdx + 1.U
      }
    }

    // ---- 收 R ----
    memData.ready := true.B
    when(memData.fire) {
      val rIdx = refillRIdx(chunkOffW - 1, 0)
      dataMem(missSetIdx)(missVictimWay)(rIdx) := memData.bits
      when(refillRIdx === (nChunks - 1).U) {
        valids(missSetIdx)(missVictimWay) := true.B
        tags(missSetIdx)(missVictimWay)   := missTag
        state := sFinishMiss
      }.otherwise {
        refillRIdx := refillRIdx + 1.U
      }
    }
  }

  // ============================================================================
  // sFinishMiss：把新行对应 chunk 返回给 Fetch
  // ----------------------------------------------------------------------------
  // 与 DCache 一致：上游可能在 refill 期间撤回请求（flush）。
  //   - 若 loadAddr.valid && bits 等于 missAddr：正常 ack + 返回数据
  //   - 若 loadAddr 已经撤回或 bits 改变：line 已安装，直接回 sIdle 丢弃孤儿数据
  //   - 若 loadAddr 还在 hold 但下游 loadData 未 ready：保持 sFinishMiss 等待
  // ============================================================================
  when(state === sFinishMiss) {
    val sameReq = loadAddr.valid && (loadAddr.bits === missAddr)
    when(sameReq) {
      loadData.valid := true.B
      loadData.bits  := dataMem(missSetIdx)(missVictimWay)(missChunkOff)
      when(loadData.fire) {
        loadAddr.ready    := true.B
        plru(missSetIdx)  := touchPLRU(plru(missSetIdx), missVictimWay)
        state             := sIdle
      }
    }.elsewhen(!loadAddr.valid || (loadAddr.bits =/= missAddr)) {
      // 上游已撤回：行已装入 cache，直接回 sIdle
      plru(missSetIdx) := touchPLRU(plru(missSetIdx), missVictimWay)
      state            := sIdle
    }
  }
}
