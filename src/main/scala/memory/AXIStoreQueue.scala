package mycpu.memory

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// AXIStoreQueue 相关 Bundle 定义
// ============================================================================
// 队列中的条目仅保存与“后续 forwarding / DRAM 写回”有关的字段，原始 store 的
// addr/data/mask 不必入队（这些信息只在 enq 同拍用于 difftest 直通输出）。
// ============================================================================
class AXISQEntry extends Bundle {
  val valid    = Bool()
  val wordAddr = UInt(30.W)
  val wstrb    = UInt(4.W)
  val wdata    = UInt(32.W)
  val storeSeq = UInt(CPUConfig.storeSeqWidth.W)
}

// ---- enq / loadReq / loadResp 三个握手通道的 Payload 定义 ----
// 全部使用标准 Decoupled，把握手与负载分离，接口更整洁，也便于以后扩展。
class SQEnqPayload extends Bundle {
  // 原始 store 信息（仅用于 difftest 直通，不必入队保存）
  val addr = UInt(32.W)
  val data = UInt(32.W)
  val mask = UInt(3.W)
  // 字节对齐后的 DRAM 写信息（入队保存，后续仲裁写回 DRAM 与 forwarding 用）
  val wstrb    = UInt(4.W)
  val wdata    = UInt(32.W)
  val storeSeq = UInt(CPUConfig.storeSeqWidth.W)
  // 字对齐地址 = addr[31:2]，由 AXIStoreQueue 内部派生，不再单独传输。
}

// ---- Memory -> AXIStoreQueue committed-store 查询接口 ----
// 纯组合查询接口：不走 Decoupled，因为查询结果当拍即可使用。
class AXISQQueryIO extends Bundle {
  val valid     = Output(Bool())
  val wordAddr  = Output(UInt(30.W))
  val loadMask  = Output(UInt(4.W))
  val fullCover = Input(Bool())
  val fwdMask   = Input(UInt(4.W))
  val fwdData   = Input(UInt(32.W))
}

// ---- 调试输出（供 SoC_Top → difftest 比对 store 的提交点）----
class AXISQDebugIO extends Bundle {
  val commitRamWen   = Output(Bool())
  val commitRamWaddr = Output(UInt(32.W))
  val commitRamWdata = Output(UInt(32.W))
  val commitRamWmask = Output(UInt(3.W))
}

// ============================================================================
// AXIStoreQueue —— 核外 committed store 队列 + 统一外部访存仲裁器
// ============================================================================
// P0 设计目标：
//   1. 接收来自 Commit 的 store enqueue（Decoupled），请求成功即视为 store 已提交；
//   2. 保存“已提交但尚未写回 DRAM”的 store，并对后续 load 提供 forwarding；
//   3. 统一仲裁 committed store 写回与 load miss 外读，并以 AXI 主设备身份发给 DRAM；
//   4. 任意时刻最多只有一笔 AXI 事务在飞，避免下游适配器需要复杂 reorder 逻辑；
//   5. `commit_ram_*` 在 enqueue 成功同拍输出，使 difftest 直接对齐 store 真实提交点。
// ============================================================================
class AXIStoreQueue(val depth: Int = CPUConfig.axiSqEntries) extends Module {
  private val idxWidth = log2Ceil(depth)
  private val ptrWidth = idxWidth + 1
  private val seqWidth = CPUConfig.storeSeqWidth
  private val storeBurstLimit = CPUConfig.axiSqStoreBurstLimit
  private val burstCntWidth = log2Ceil(storeBurstLimit + 1)

  // ---- 与 myCPU 的握手接口（统一 Decoupled）----
  val enq      = IO(Flipped(Decoupled(new SQEnqPayload)))
  val query    = IO(Flipped(new AXISQQueryIO))
  val loadAddr = IO(Flipped(Decoupled(UInt(32.W))))
  val loadData = IO(Decoupled(UInt(32.W)))

  // ---- 与 DRAM_AXIInterface 的 AXI 主设备接口 ----
  val axi = IO(new AXIBundle())

  // ---- 调试观测 ----
  val debug = IO(new AXISQDebugIO)

  // 队列物理存储：按提交顺序入队，按最老顺序写回。
  val entries = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new AXISQEntry))))
  val head = RegInit(0.U(ptrWidth.W))
  val tail = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U((ptrWidth + 1).W))
  private def idx(ptr: UInt): UInt = ptr(idxWidth - 1, 0)
  private def seqNewerOrEq(a: UInt, b: UInt): Bool = !(a - b)(seqWidth - 1)

  val queueFull = count === depth.U
  val hasStore  = count =/= 0.U
  val headIdx   = idx(head)
  val tailIdx   = idx(tail)
  val headEntry = entries(headIdx)

  // ===================== Enqueue 接口 =====================
  enq.ready := !queueFull
  val enqFire = enq.fire
  // 字对齐地址直接由 enq.bits.addr 派生，无需上游再传一份。
  val enqWordAddr = enq.bits.addr(31, 2)

  when(enqFire) {
    entries(tailIdx).valid    := true.B
    entries(tailIdx).wordAddr := enqWordAddr
    entries(tailIdx).wstrb    := enq.bits.wstrb
    entries(tailIdx).wdata    := enq.bits.wdata
    entries(tailIdx).storeSeq := enq.bits.storeSeq
  }

  // ===================== Committed-store 查询 =====================
  // 对每个字节选择“对当前 load 可见的最年轻已提交 store”。
  val perByteFwdValid = Wire(Vec(4, Bool()))
  val perByteFwdData = Wire(Vec(4, UInt(8.W)))

  for (b <- 0 until 4) {
    val (bestValid, _, bestByte) = (0 until depth).foldLeft(
      (false.B, 0.U(seqWidth.W), 0.U(8.W))
    ) { case ((hasBest, bestSeqSoFar, bestByteSoFar), i) =>
      val candidate = entries(i).valid &&
        (entries(i).wordAddr === query.wordAddr) &&
        entries(i).wstrb(b) &&
        query.loadMask(b)
      val chooseThis = candidate && (!hasBest || seqNewerOrEq(entries(i).storeSeq, bestSeqSoFar))
      (
        hasBest || candidate,
        Mux(chooseThis, entries(i).storeSeq, bestSeqSoFar),
        Mux(chooseThis, entries(i).wdata(b * 8 + 7, b * 8), bestByteSoFar)
      )
    }
    perByteFwdValid(b) := bestValid
    perByteFwdData(b) := bestByte
  }

  val rawQueryMask = perByteFwdValid.asUInt
  query.fwdMask := Mux(query.valid, rawQueryMask, 0.U)
  query.fwdData := Mux(
    query.valid,
    Cat(perByteFwdData(3), perByteFwdData(2), perByteFwdData(1), perByteFwdData(0)),
    0.U
  )
  query.fullCover := query.valid && ((rawQueryMask & query.loadMask) === query.loadMask)

  // ===================== 统一 AXI 读写仲裁 =====================
  val sIdle :: sLoadWaitR :: sStoreWaitB :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // 当 load miss 已经在等待，而 committed store 持续堆积时，限制连续 store 优先次数，避免 load 饥饿。
  val consecutiveStoreWins = RegInit(0.U(burstCntWidth.W))
  val loadPending = loadAddr.valid
  val forceLoad   = loadPending && hasStore && (consecutiveStoreWins === storeBurstLimit.U)
  val chooseStore = hasStore && (!loadPending || !forceLoad)
  val chooseLoad  = loadPending && (!hasStore || forceLoad)

  // ---- AXI 各通道默认值 ----
  axi.aw.valid := false.B
  axi.aw.bits.id   := 0.U
  axi.aw.bits.addr := 0.U
  axi.w.valid  := false.B
  axi.w.bits.data := 0.U
  axi.w.bits.strb := 0.U
  axi.b.ready  := false.B
  axi.ar.valid := false.B
  axi.ar.bits.id   := 0.U
  axi.ar.bits.addr := 0.U
  axi.r.ready  := false.B

  loadAddr.ready := false.B
  loadData.valid := false.B
  loadData.bits  := 0.U

  val storeRespFire = WireInit(false.B)
  val loadRespFire  = WireInit(false.B)

  switch(state) {
    is(sIdle) {
      when(chooseStore) {
        // 同拍发起 AW 与 W；DRAM_AXIInterface 仅在两者同拍 valid 时才完成握手。
        axi.aw.valid     := true.B
        axi.aw.bits.addr := Cat(headEntry.wordAddr, 0.U(2.W))
        axi.w.valid      := true.B
        axi.w.bits.data  := headEntry.wdata
        axi.w.bits.strb  := headEntry.wstrb
        when(axi.aw.ready && axi.w.ready) {
          state := sStoreWaitB
          when(loadPending) {
            consecutiveStoreWins := Mux(
              consecutiveStoreWins === storeBurstLimit.U,
              consecutiveStoreWins,
              consecutiveStoreWins + 1.U
            )
          }.otherwise {
            consecutiveStoreWins := 0.U
          }
        }
      }.elsewhen(chooseLoad) {
        axi.ar.valid     := true.B
        axi.ar.bits.addr := loadAddr.bits
        loadAddr.ready    := axi.ar.ready
        when(loadAddr.fire) {
          state := sLoadWaitR
          consecutiveStoreWins := 0.U
        }
      }.otherwise {
        consecutiveStoreWins := 0.U
      }
    }

    is(sLoadWaitR) {
      // 把 R 通道直接桥接到 loadResp 的 Decoupled。
      loadData.valid := axi.r.valid
      loadData.bits  := axi.r.bits.data
      axi.r.ready    := loadData.ready
      loadRespFire   := loadData.fire
      when(loadRespFire) {
        state := sIdle
      }
    }

    is(sStoreWaitB) {
      // Store 写响应只供队列内部消费，因此对 B 通道始终 ready。
      axi.b.ready := true.B
      storeRespFire := axi.b.valid
      when(storeRespFire) {
        state := sIdle
      }
    }
  }

  // ===================== 队列头释放 / 指针更新 =====================
  when(storeRespFire) {
    entries(headIdx).valid := false.B
  }

  when(enqFire && !storeRespFire) {
    tail := tail + 1.U
    count := count + 1.U
  }.elsewhen(!enqFire && storeRespFire) {
    head := head + 1.U
    count := count - 1.U
  }.elsewhen(enqFire && storeRespFire) {
    tail := tail + 1.U
    head := head + 1.U
  }

  // ===================== difftest 调试口 =====================
  // 直接使用 enqueue 成功脉冲：在当前架构下，store 在成功进入 AXIStoreQueue
  // 的同拍即视为完成提交，difftest 也按此时刻完成 RAM 写事件比对。
  debug.commitRamWen   := enqFire
  debug.commitRamWaddr := enq.bits.addr
  debug.commitRamWdata := enq.bits.data
  debug.commitRamWmask := enq.bits.mask
}
