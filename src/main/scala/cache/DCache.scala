package mycpu.cache

import chisel3._
import chisel3.util._
import mycpu.CPUConfig
import mycpu.memory.{AXIBundle, AXIResp, SQEnqPayload, AXISQQueryIO}
import mycpu.dataLane.LaneCapability

// ============================================================================
// DCache —— 4路组相联写回写分配数据Cache
//
// 设计目标：完全替代 AXIStoreQueue，对外接口语义与其一致（直接替换）。
//
// 关键设计决策（相对 spec 的取舍，优先correctness）：
//   1. 单 MSHR blocking miss（MVP）：同时只处理 1 个 miss；自然保证 sqLoadData
//      按 sqLoadAddr.fire 顺序 FIFO 返回（满足 Memory.scala mshrInFlightFifo 假设）。
//   2. Forwarding 只查 spq（已提交但尚未写入 Cache 的 store）；已入 Cache 的 store
//      由 load 命中读出，无需单独 forwarding。
//   3. AXI 写回：每 word 发 1 次 AW+W，DRAM 端口单 outstanding write；
//      axi.b.ready 始终为 true，B 响应在背景消化，不阻塞写回节奏。
//   4. Refill：按序发 16 个 AR，按序收 16 个 R，直接写入 dataMem；
//      与 DRAM_AXIInterface 的 readReqFifo/readRespFifo 流水对接。
//   5. query 接口使用 Flipped(Vec(...)) 与 AXIStoreQueue 一致（spec 头部写法有误）。
//
// 不变量：
//   - 任意时刻至多 1 个 miss 在飞（单 MSHR）
//   - sWriteBack 中 AW+W 始终同拍 valid（由状态机保证）
//   - loadData 响应顺序与 loadAddr.fire 顺序严格一致（blocking miss 天然保证）
//   - enq（store 提交）路径不经过状态机，commit 同拍即可入队
// ============================================================================
class DCache extends Module {

  // 当前实现仅支持 4 路，PLRU 硬编码 3 bit 树
  require(CPUConfig.cacheAssoc == 4,
    s"DCache 仅支持 4 路组相联，当前 cacheAssoc=${CPUConfig.cacheAssoc}")

  // ============================================================================
  // 对外接口（与 AXIStoreQueue 完全一致，可直接替换）
  // ============================================================================
  val enq      = IO(Flipped(Decoupled(new SQEnqPayload)))
  // query 接口：Flipped(Vec) 与 AXIStoreQueue 保持一致；
  //   DCache 作为 responder，valid/wordAddr/loadMask 为 Input；
  //   fullCover/fwdMask/fwdData 为 Output。
  val query    = IO(Flipped(Vec(LaneCapability.loadLanes.size, new AXISQQueryIO)))
  val loadAddr = IO(Flipped(Decoupled(UInt(32.W))))
  val loadData = IO(Decoupled(UInt(32.W)))
  val axi      = IO(new AXIBundle())

  // ============================================================================
  // Cache 参数（从 CPUConfig 读取）
  // ============================================================================
  private val nSets    = CPUConfig.dcacheNumSets         // 组数（默认 16）
  private val nWays    = CPUConfig.cacheAssoc            // 路数（固定 4）
  private val tagW     = CPUConfig.dcacheTagWidth        // tag 位宽（默认 22）
  private val idxW     = CPUConfig.dcacheSetIdxWidth     // index 位宽（默认 4）
  private val nWords   = CPUConfig.cacheLineWords        // 每行 word 数（16）
  private val offW     = CPUConfig.cacheOffsetWidth      // byte offset 位宽（6）
  private val spqDepth = CPUConfig.axiSqEntries          // spq 深度（16）
  private val seqW     = CPUConfig.storeSeqWidth         // storeSeq 位宽（8）
  private val wayW     = log2Ceil(nWays)                 // way 索引位宽（2）
  private val wIdxW    = log2Ceil(nWords)                // word 索引位宽（4）

  // ---- 地址字段分解辅助函数 ----
  // 地址格式：[31 .. offW+idxW | offW+idxW-1 .. offW | offW-1 .. 2 | 1:0]
  //           [   tag(22)      |     setIdx(4)        |  wordOff(4)  | byteOff ]
  def addrTag(a: UInt):     UInt = a(31, offW + idxW)
  def addrSetIdx(a: UInt):  UInt = a(offW + idxW - 1, offW)
  def addrWordOff(a: UInt): UInt = a(offW - 1, 2)

  // ============================================================================
  // Cache 存储阵列（寄存器实现）
  // ============================================================================
  // tags/dataMem 不需初始化（valid=false 时内容无效）
  val tags    = Reg(Vec(nSets, Vec(nWays, UInt(tagW.W))))
  val dataMem = Reg(Vec(nSets, Vec(nWays, Vec(nWords, UInt(32.W)))))
  // valid/dirty 需初始化为 false
  val valids  = RegInit(VecInit(Seq.fill(nSets)(VecInit(Seq.fill(nWays)(false.B)))))
  val dirties = RegInit(VecInit(Seq.fill(nSets)(VecInit(Seq.fill(nWays)(false.B)))))

  // ============================================================================
  // 3-bit 树形 Pseudo-LRU（4 路）
  //
  // 位布局（bit2=根, bit1=左子, bit0=右子）：
  //   bit2: 0→下次替换左子树(way0/1)，1→下次替换右子树(way2/3)
  //   bit1: 0→(左子树中)下次替换 way0，1→替换 way1
  //   bit0: 0→(右子树中)下次替换 way2，1→替换 way3
  //
  // 访问语义：访问某 way 后，更新路径上各 bit 使其"指向"另一侧，
  //           即记录"最近访问的是哪侧"，下次替换选另一侧。
  // ============================================================================
  val plru = RegInit(VecInit(Seq.fill(nSets)(0.U(3.W))))

  // plruVictim：根据 PLRU bits 选出受害者 way（LRU way）
  def plruVictim(p: UInt): UInt =
    Mux(p(2) === 0.U,
      Mux(p(1) === 0.U, 0.U(wayW.W), 1.U(wayW.W)),  // 选左子树中的 LRU
      Mux(p(0) === 0.U, 2.U(wayW.W), 3.U(wayW.W)))   // 选右子树中的 LRU

  // touchPLRU：访问 way w 后更新 PLRU bits
  //   inRight = w(1): way2/3 在右子树，way0/1 在左子树
  //   leaf    = w(0): 在子树中是左叶(0)还是右叶(1)
  def touchPLRU(p: UInt, w: UInt): UInt = {
    val inRight  = w(1)
    val leaf     = w(0)
    // bit2 = ~inRight：访问左→下次选右(1)，访问右→下次选左(0)
    // bit1 仅在访问左子树时更新：访问 way0→下次选 way1(1)，访问 way1→下次选 way0(0)
    // bit0 仅在访问右子树时更新：访问 way2→下次选 way3(1)，访问 way3→下次选 way2(0)
    val newBit2 = ~inRight
    val newBit1 = Mux(inRight === 0.U, ~leaf, p(1))
    val newBit0 = Mux(inRight === 1.U, ~leaf, p(0))
    Cat(newBit2, newBit1, newBit0)
  }

  // ============================================================================
  // storePendingFifo（spq）—— 已提交但尚未写入 Cache 的 store 队列
  //
  // 设计要点：
  //   - enq 路径不经过状态机，commit 路径可即时入队
  //   - spqMem(i).valid 用于 forwarding 扫描（全量扫描 0..spqDepth-1）
  //   - spq 队头在 sIdle 命中或 sFinishMiss store 完成后弹出
  // ============================================================================
  class SpqEntry extends Bundle {
    val valid    = Bool()       // 此项是否在队列中（用于 forwarding 扫描）
    val addr     = UInt(32.W)   // store 字节地址
    val wstrb    = UInt(4.W)    // 字节使能
    val wdata    = UInt(32.W)   // 写数据（字级，对齐到字）
    val storeSeq = UInt(seqW.W) // store 序号（用于 forwarding 优先级，循环比较）
    val mask     = UInt(3.W)    // funct3 级别 mask，difftest 用
    val data     = UInt(32.W)   // 原始 data 字段，difftest 用
  }

  private val spqIdxW = log2Ceil(spqDepth)
  private val spqPtrW = spqIdxW + 1

  // spq 存储与指针
  val spqMem   = RegInit(VecInit(Seq.fill(spqDepth)(0.U.asTypeOf(new SpqEntry))))
  val spqHead  = RegInit(0.U(spqPtrW.W))
  val spqTail  = RegInit(0.U(spqPtrW.W))
  val spqCount = RegInit(0.U((spqPtrW + 1).W))
  // 为了让外部仿真 / difftest 框架可以通过统一名字 `count` 读取 spq 占用，
  // 在 Chisel 层面直接给 spqCount 起一个别名 `count`（同一个寄存器，FIRRTL 不复制）。
  spqCount.suggestName("count")

  def spqIdx(p: UInt): UInt = p(spqIdxW - 1, 0)
  val spqFull    = spqCount === spqDepth.U
  val spqEmpty   = spqCount === 0.U
  val spqHeadIdx = spqIdx(spqHead)
  val spqTailIdx = spqIdx(spqTail)

  // seqNewerOrEq：循环序号比较（a 是否比 b 更新或相等）
  def seqNewerOrEq(a: UInt, b: UInt): Bool = !(a - b)(seqW - 1)

  // ============================================================================
  // 主状态机状态定义
  // ============================================================================
  val sIdle :: sWriteBack :: sRefill :: sFinishMiss :: Nil = Enum(4)
  val state = RegInit(sIdle)

  // ---- MSHR 寄存器（miss 上下文，单条）----
  val missIsLoad    = RegInit(false.B)    // true=load miss，false=store miss
  val missAddr      = Reg(UInt(32.W))     // miss 字节地址（已保存）
  val missSetIdx    = Reg(UInt(idxW.W))   // miss 的 set index
  val missVictimWay = Reg(UInt(wayW.W))   // 选出的受害者 way
  val missNewTag    = Reg(UInt(tagW.W))   // miss 地址的 tag（新行安装后使用）

  // ---- 写回计数器（word 索引 0..15）----
  val wbWordIdx = RegInit(0.U(5.W))

  // ---- Refill 计数器 ----
  val refillArIdx = RegInit(0.U(5.W))  // 已发出 AR 数量
  val refillRIdx  = RegInit(0.U(5.W))  // 已接收 R 数量

  // ============================================================================
  // loadData 响应队列（深度 2）
  // 解耦 hit/miss 响应产生时机与下游消费背压；
  // 深度 2 足够（blocking miss 保证最多 1 个 response in flight）。
  // ============================================================================
  val loadDataQ = Module(new Queue(UInt(32.W), 2))
  loadData <> loadDataQ.io.deq
  // 默认不入队（由状态机各分支覆盖）
  loadDataQ.io.enq.valid := false.B
  loadDataQ.io.enq.bits  := 0.U

  // ============================================================================
  // AXI 接口默认值
  // 说明：axi.b.ready 始终为 true，无论何种状态都消化写响应，
  //       不需要等 B 才能继续，避免死锁。
  // ============================================================================
  axi.aw.valid     := false.B
  axi.aw.bits.id   := 0.U
  axi.aw.bits.addr := 0.U
  axi.w.valid      := false.B
  axi.w.bits.data  := 0.U
  axi.w.bits.strb  := 0.U
  axi.b.ready      := true.B   // 始终消化 B 通道
  axi.ar.valid     := false.B
  axi.ar.bits.id   := 0.U
  axi.ar.bits.addr := 0.U
  axi.r.ready      := false.B

  // loadAddr 默认不 ready（仅在命中或 miss 处理完毕时拉高）
  loadAddr.ready := false.B

  // ============================================================================
  // Enqueue 路径：store 提交入 spq
  // 不经过状态机，enq.fire 同拍即入队，保证 commit 路径低延迟
  // ============================================================================
  enq.ready := !spqFull

  val enqFire = enq.fire
  when(enqFire) {
    spqMem(spqTailIdx).valid    := true.B
    spqMem(spqTailIdx).addr     := enq.bits.addr
    spqMem(spqTailIdx).wstrb    := enq.bits.wstrb
    spqMem(spqTailIdx).wdata    := enq.bits.wdata
    spqMem(spqTailIdx).storeSeq := enq.bits.storeSeq
    spqMem(spqTailIdx).mask     := enq.bits.mask
    spqMem(spqTailIdx).data     := enq.bits.data
  }

  // ---- spq 指针维护 ----
  // spqPopWire 由状态机在以下两处拉高：
  //   1. sIdle 中 store hit → 直接写 cache，同拍弹出
  //   2. sFinishMiss 中 store miss 完成 → 写完 cache 后弹出
  val spqPopWire = WireInit(false.B)

  when(enqFire && !spqPopWire) {
    spqTail  := spqTail  + 1.U
    spqCount := spqCount + 1.U
  }.elsewhen(!enqFire && spqPopWire) {
    spqMem(spqHeadIdx).valid := false.B
    spqHead  := spqHead  + 1.U
    spqCount := spqCount - 1.U
  }.elsewhen(enqFire && spqPopWire) {
    // 同拍一进一出，count 不变
    spqMem(spqHeadIdx).valid := false.B
    spqTail  := spqTail  + 1.U
    spqHead  := spqHead  + 1.U
  }

  // ============================================================================
  // Forwarding 查询（纯组合电路，仅查 spq）
  //
  // 语义：对每个 load lane，在 spq 所有有效项中按字节找最新（storeSeq 最大）
  //       匹配的 store，返回 fwdMask/fwdData/fullCover。
  //
  // 设计简化说明：
  //   已写入 Cache 的 store 由 load 命中 Cache 后直接读出，不需要额外 forwarding；
  //   因此只需扫描 spq（与 AXIStoreQueue 的 forwarding 逻辑完全等价）。
  // ============================================================================
  for (q <- 0 until LaneCapability.loadLanes.size) {
    val perByteFwdValid = Wire(Vec(4, Bool()))
    val perByteFwdData  = Wire(Vec(4, UInt(8.W)))

    for (b <- 0 until 4) {
      // foldLeft 在所有 spq 项中找"最新匹配"的字节
      val (bestValid, _, bestByte) = (0 until spqDepth).foldLeft(
        (false.B, 0.U(seqW.W), 0.U(8.W))
      ) { case ((hasBest, bestSeqSoFar, bestByteSoFar), i) =>
        // 候选条件：spq 项有效 + 同字地址 + wstrb(b)=1 + loadMask(b)=1
        val cand = spqMem(i).valid &&
          (spqMem(i).addr(31, 2) === query(q).wordAddr) &&
          spqMem(i).wstrb(b) &&
          query(q).loadMask(b)
        // 当前项比已知最优更新（或无最优）则选当前项
        val pick = cand && (!hasBest || seqNewerOrEq(spqMem(i).storeSeq, bestSeqSoFar))
        (
          hasBest || cand,
          Mux(pick, spqMem(i).storeSeq,              bestSeqSoFar),
          Mux(pick, spqMem(i).wdata(b * 8 + 7, b * 8), bestByteSoFar)
        )
      }
      perByteFwdValid(b) := bestValid
      perByteFwdData(b)  := bestByte
    }

    val rawMask = perByteFwdValid.asUInt
    query(q).fwdMask  := Mux(query(q).valid, rawMask, 0.U)
    query(q).fwdData  := Mux(query(q).valid,
      Cat(perByteFwdData(3), perByteFwdData(2), perByteFwdData(1), perByteFwdData(0)), 0.U)
    query(q).fullCover := query(q).valid && ((rawMask & query(q).loadMask) === query(q).loadMask)
  }

  // ============================================================================
  // 载入侧 Forwarding overlay（针对 loadAddr.bits，纯组合）
  // ----------------------------------------------------------------------------
  // 背景：新架构下 AR 来自 AXIStoreQueue（已经做完 CPU 端 forwarding），并
  //       不会再去查 DCache 的 query 端口。因此 DCache 在 cache 命中或 miss
  //       完成时若不主动 overlay 内部 spq 中尚未 drain 的 store，就会让
  //       上层（AXIStoreQueue/CPU）读到旧值。
  // 语义：按字节扫描 spq，找到 storeSeq 最新且 wstrb 命中该字节的项，
  //       overlay 到从 cache 行读出的 word 上，得到对 CPU 视角正确的数据。
  // ============================================================================
  val loadWordAddr      = loadAddr.bits(31, 2)
  val loadFwdByteValid  = Wire(Vec(4, Bool()))
  val loadFwdByteData   = Wire(Vec(4, UInt(8.W)))
  for (b <- 0 until 4) {
    val (bestValid, _, bestByte) = (0 until spqDepth).foldLeft(
      (false.B, 0.U(seqW.W), 0.U(8.W))
    ) { case ((hasBest, bestSeqSoFar, bestByteSoFar), i) =>
      val cand = spqMem(i).valid &&
                 (spqMem(i).addr(31, 2) === loadWordAddr) &&
                 spqMem(i).wstrb(b)
      val pick = cand && (!hasBest || seqNewerOrEq(spqMem(i).storeSeq, bestSeqSoFar))
      (
        hasBest || cand,
        Mux(pick, spqMem(i).storeSeq,                 bestSeqSoFar),
        Mux(pick, spqMem(i).wdata(b * 8 + 7, b * 8),  bestByteSoFar)
      )
    }
    loadFwdByteValid(b) := bestValid
    loadFwdByteData(b)  := bestByte
  }
  // overlay：cache 读出的 word 按字节叠加 spq 命中字节
  def overlaySpq(word: UInt): UInt = {
    val bytes = Wire(Vec(4, UInt(8.W)))
    for (i <- 0 until 4) {
      bytes(i) := Mux(loadFwdByteValid(i), loadFwdByteData(i), word(i * 8 + 7, i * 8))
    }
    Cat(bytes(3), bytes(2), bytes(1), bytes(0))
  }

  // ============================================================================
  // 辅助：按 wstrb 合并字节写入 word
  // ============================================================================
  def applyStore(oldWord: UInt, wdata: UInt, wstrb: UInt): UInt = {
    val bytes = Wire(Vec(4, UInt(8.W)))
    for (i <- 0 until 4) {
      bytes(i) := Mux(wstrb(i), wdata(i * 8 + 7, i * 8), oldWord(i * 8 + 7, i * 8))
    }
    Cat(bytes(3), bytes(2), bytes(1), bytes(0))
  }

  // ============================================================================
  // 主状态机
  // ============================================================================
  switch(state) {

    // --------------------------------------------------------------------------
    // sIdle：命中单周期响应 + miss 触发 + store drain
    //
    // 优先级：load > store（避免 load 饥饿）
    // --------------------------------------------------------------------------
    is(sIdle) {
      when(loadAddr.valid) {
        // ---- Load 请求处理 ----
        val lTag    = addrTag(loadAddr.bits)
        val lSetIdx = addrSetIdx(loadAddr.bits)
        val lWOff   = addrWordOff(loadAddr.bits)

        // 并行比较所有 way 的 tag 和 valid
        val hitVec = VecInit((0 until nWays).map { w =>
          valids(lSetIdx)(w) && (tags(lSetIdx)(w) === lTag)
        })
        val isHit  = hitVec.asUInt.orR
        val hitWay = OHToUInt(hitVec.asUInt)  // OHToUInt 取首个命中 way

        when(isHit) {
          // ---- 命中：同周期响应 ----
          // 仅在 loadDataQ 有空间时才接受（避免 loadData 堵塞）
          when(loadDataQ.io.enq.ready) {
            loadDataQ.io.enq.valid := true.B
            // overlaySpq：在 cache 命中数据上叠加 spq 中尚未 drain 的字节
            loadDataQ.io.enq.bits  := overlaySpq(dataMem(lSetIdx)(hitWay)(lWOff))
            loadAddr.ready         := true.B
            // 更新 PLRU（记录最近访问了此 way）
            plru(lSetIdx)          := touchPLRU(plru(lSetIdx), hitWay)
          }
          // 若 loadDataQ 满，stall（loadAddr.ready 保持 false）
        }.otherwise {
          // ---- Load Miss：锁存 MSHR 上下文，选受害者，进入写回或填充 ----
          // 注意：loadAddr.ready 保持 false，upstream 持续等待
          missIsLoad    := true.B
          missAddr      := loadAddr.bits
          missSetIdx    := lSetIdx
          missNewTag    := lTag
          val victim    =  plruVictim(plru(lSetIdx))
          missVictimWay := victim
          when(dirties(lSetIdx)(victim)) {
            // victim line 是 dirty 的，需要先写回 DRAM
            wbWordIdx := 0.U
            state     := sWriteBack
          }.otherwise {
            // victim line 干净（无效或 clean），直接填充
            refillArIdx := 0.U
            refillRIdx  := 0.U
            state       := sRefill
          }
        }

      }.elsewhen(!spqEmpty) {
        // ---- Store Drain：处理 spq 队头 ----
        val sEntry  = spqMem(spqHeadIdx)
        val sTag    = addrTag(sEntry.addr)
        val sSetIdx = addrSetIdx(sEntry.addr)
        val sWOff   = addrWordOff(sEntry.addr)

        val hitVec = VecInit((0 until nWays).map { w =>
          valids(sSetIdx)(w) && (tags(sSetIdx)(w) === sTag)
        })
        val isHit  = hitVec.asUInt.orR
        val hitWay = OHToUInt(hitVec.asUInt)

        when(isHit) {
          // ---- Store Hit：直接写 cache（按字节），置 dirty，弹出队头 ----
          val oldWord = dataMem(sSetIdx)(hitWay)(sWOff)
          dataMem(sSetIdx)(hitWay)(sWOff) := applyStore(oldWord, sEntry.wdata, sEntry.wstrb)
          dirties(sSetIdx)(hitWay)        := true.B
          plru(sSetIdx)                   := touchPLRU(plru(sSetIdx), hitWay)
          spqPopWire                      := true.B  // 弹出队头
        }.otherwise {
          // ---- Store Miss：同 load miss，触发写回或填充 ----
          missIsLoad    := false.B
          missAddr      := sEntry.addr
          missSetIdx    := sSetIdx
          missNewTag    := sTag
          val victim    = plruVictim(plru(sSetIdx))
          missVictimWay := victim
          when(dirties(sSetIdx)(victim)) {
            wbWordIdx := 0.U
            state     := sWriteBack
          }.otherwise {
            refillArIdx := 0.U
            refillRIdx  := 0.U
            state       := sRefill
          }
        }
      }
    }

    // --------------------------------------------------------------------------
    // sWriteBack：把 victim line 的 16 个 word 逐拍写回 DRAM
    //
    // 协议：每拍 AW+W 同拍 valid → DRAM 接受后 → DRAM 发 B（被 b.ready=true 消化）
    //        → 下一拍 DRAM 再接受下一个 AW+W。
    // 约 2 周期/word × 16 word = 32 周期完成写回。
    // 写回完成后直接进 sRefill，不等最后一个 B（它会在 sRefill 中被 b.ready 消化）。
    // --------------------------------------------------------------------------
    is(sWriteBack) {
      when(wbWordIdx < nWords.U) {
        // 受害者行中第 wbWordIdx 个 word 的字节地址
        // 地址构造：tag(22) ++ setIdx(4) ++ wordIdx(4) ++ 00(2) = 32 bit
        val wbAddr = Cat(
          tags(missSetIdx)(missVictimWay),  // tag
          missSetIdx,                        // set index
          wbWordIdx(wIdxW - 1, 0),          // word index（行内）
          0.U(2.W)                           // 字节偏移（word 对齐）
        )
        axi.aw.valid     := true.B
        axi.aw.bits.addr := wbAddr
        axi.w.valid      := true.B
        axi.w.bits.data  := dataMem(missSetIdx)(missVictimWay)(wbWordIdx(wIdxW - 1, 0))
        axi.w.bits.strb  := "b1111".U  // 写回整个 word，无字节使能过滤

        // DRAM_AXIInterface 要求 AW+W 同拍 valid 且 ready 同拍握手
        when(axi.aw.fire && axi.w.fire) {
          when(wbWordIdx === (nWords - 1).U) {
            // 最后一个 word 发出，转入 refill（不等 B 通道）
            refillArIdx := 0.U
            refillRIdx  := 0.U
            state       := sRefill
          }.otherwise {
            wbWordIdx := wbWordIdx + 1.U
          }
        }
        // axi.b.ready = true（由顶层默认设置），DRAM B 响应在背景消化
      }
    }

    // --------------------------------------------------------------------------
    // sRefill：发 16 个 AR，收 16 个 R，逐 word 写入 dataMem
    //
    // AR 与 R 可流水执行：
    //   - AR 按序发出，受 axi.ar.ready（DRAM readReqFifo 背压）控制
    //   - R  按序接收，受 axi.r.valid 控制
    //   - DRAM_AXIInterface 的 readReqFifo(depth=2) + readRespFifo(depth=2) 提供缓冲
    //
    // 收到全部 16 个 R 后安装 cache 行：设 valid=true, dirty=false, tag=missNewTag
    // --------------------------------------------------------------------------
    is(sRefill) {
      val newLineAddrBase = Cat(missNewTag, missSetIdx, 0.U(offW.W))  // 新行基地址

      // ---- 发 AR（按序，每拍最多 1 个）----
      when(refillArIdx < nWords.U) {
        val arAddr = Cat(
          missNewTag,
          missSetIdx,
          refillArIdx(wIdxW - 1, 0),  // word index
          0.U(2.W)
        )
        axi.ar.valid     := true.B
        axi.ar.bits.addr := arAddr
        when(axi.ar.fire) {
          refillArIdx := refillArIdx + 1.U
        }
      }

      // ---- 收 R（写入 dataMem，按序）----
      axi.r.ready := true.B
      when(axi.r.valid) {
        val rIdx = refillRIdx(wIdxW - 1, 0)
        // 直接写入 dataMem（省去 refillLine 中间寄存器）
        dataMem(missSetIdx)(missVictimWay)(rIdx) := axi.r.bits.data

        when(refillRIdx === (nWords - 1).U) {
          // 全部 16 个 word 收齐，安装 cache 行
          // dirty=false（刚从 DRAM 填充，尚未被 store 修改）
          valids(missSetIdx)(missVictimWay)  := true.B
          dirties(missSetIdx)(missVictimWay) := false.B
          tags(missSetIdx)(missVictimWay)    := missNewTag
          state := sFinishMiss
        }.otherwise {
          refillRIdx := refillRIdx + 1.U
        }
      }
    }

    // --------------------------------------------------------------------------
    // sFinishMiss：根据 missIsLoad 完成后续操作
    //
    // Load miss：从新行读出对应 word → 推入 loadDataQ → 消费 loadAddr → 回 sIdle
    // Store miss：把 spq 队头按字节写入新行 → dirty=1 → 弹出队头 → 回 sIdle
    // --------------------------------------------------------------------------
    is(sFinishMiss) {
      when(missIsLoad) {
        // ---- Load miss 完成：读新行数据，推入响应队列 ----
        // 关键不变量：DCache 必须严格维护 loadAddr.fire 与 loadData.fire 1:1 配对。
        // miss 进入时只是"锁存了 loadAddr.bits"，并未通过 .fire 真正 ACK 上游。
        // 若上游在 refill 期间因 flush 撤回了该请求（valid 不再为高，或 bits 已变），
        // 此时把数据推入 loadDataQ 会成为"孤儿数据"——后续合法 fire 会取到错误数据。
        // 因此仅当 loadAddr.valid 且 bits 仍等于 missAddr 时，才完成 .fire 与数据回推；
        // 否则丢弃数据，line 已经在 cache 中，后续命中会正常返回。
        val wOff       = addrWordOff(missAddr)
        val canCommit  = loadAddr.valid && (loadAddr.bits === missAddr) && loadDataQ.io.enq.ready
        when(canCommit) {
          loadDataQ.io.enq.valid := true.B
          // overlaySpq：refill 完成后的新行也要叠加 spq 中尚未 drain 的字节
          loadDataQ.io.enq.bits  := overlaySpq(dataMem(missSetIdx)(missVictimWay)(wOff))
          loadAddr.ready         := true.B
          plru(missSetIdx)       := touchPLRU(plru(missSetIdx), missVictimWay)
          state := sIdle
        }.elsewhen(!loadAddr.valid || (loadAddr.bits =/= missAddr)) {
          // 上游已撤回该 miss 请求：line 已安装好，直接回 sIdle 丢弃孤儿数据
          plru(missSetIdx)       := touchPLRU(plru(missSetIdx), missVictimWay)
          state := sIdle
        }
        // 否则（loadAddr 匹配但 loadDataQ 满）保持 sFinishMiss 状态等待
      }.otherwise {
        // ---- Store miss 完成：把 spq 队头的字节写入新行 ----
        val sEntry = spqMem(spqHeadIdx)
        val wOff   = addrWordOff(sEntry.addr)
        val oldW   = dataMem(missSetIdx)(missVictimWay)(wOff)
        dataMem(missSetIdx)(missVictimWay)(wOff) := applyStore(oldW, sEntry.wdata, sEntry.wstrb)
        dirties(missSetIdx)(missVictimWay)       := true.B
        plru(missSetIdx)                         := touchPLRU(plru(missSetIdx), missVictimWay)
        spqPopWire := true.B  // 弹出 spq 队头
        state      := sIdle
      }
    }
  }

  // ============================================================================
  // 关键不变量断言
  // ============================================================================
  // AW+W 必须同拍 valid（DRAM_AXIInterface 要求）
  // 单事务 AXI master：AW 与 W 必须同拍有效（DRAM_AXIInterface 协议要求）
  assert(!(axi.aw.valid && !axi.w.valid), "DCache: axi.aw.valid must imply axi.w.valid in the same cycle")
  // 单 MSHR：在写回 / 填充阶段不应接受新的 loadAddr。
  // sFinishMiss 完成 load miss 时会把 ready 拉高一拍消费当前 miss 请求，因此排除该状态。
  when(state === sWriteBack || state === sRefill) {
    assert(!loadAddr.ready, "DCache: single MSHR requires loadAddr.ready to be false during miss handling")
  }
}
