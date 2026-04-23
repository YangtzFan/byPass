package mycpu

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ==========================================================================
// AXIStoreQueue 相关 Bundle 定义
// ==========================================================================
// committed store 在核外队列中的存储格式。
// 这里同时保留“原始 store 信息”和“对齐后的字节写信息”，
// 这样既能满足 load forwarding / 实际写回，也能在 enqueue 成功的同拍直接驱动 difftest 调试口。
class AXISQEntry extends Bundle {
  val valid    = Bool()
  val addr     = UInt(32.W)
  val data     = UInt(32.W)
  val mask     = UInt(3.W)
  val wordAddr = UInt(30.W)
  val wstrb    = UInt(4.W)
  val wdata    = UInt(32.W)
  val storeSeq = UInt(CPUConfig.storeSeqWidth.W)
}

// ---- Commit -> AXIStoreQueue 入队接口 ----
// 方向以 Commit / myCPU 发送方为基准定义。
class AXISQEnqIO extends Bundle {
  val valid    = Output(Bool())
  val ready    = Input(Bool())
  val addr     = Output(UInt(32.W))
  val data     = Output(UInt(32.W))
  val mask     = Output(UInt(3.W))
  val wordAddr = Output(UInt(30.W))
  val wstrb    = Output(UInt(4.W))
  val wdata    = Output(UInt(32.W))
  val storeSeq = Output(UInt(CPUConfig.storeSeqWidth.W))
}

// ---- Memory -> AXIStoreQueue committed-store 查询接口 ----
// 这是纯组合查询接口：Memory 先给出当前还未覆盖的字节需求，
// AXIStoreQueue 返回 committed queue 中能够提供的字节掩码和数据。
class AXISQQueryIO extends Bundle {
  val valid     = Output(Bool())
  val wordAddr  = Output(UInt(30.W))
  val loadMask  = Output(UInt(4.W))
  val fullCover = Input(Bool())
  val fwdMask   = Input(UInt(4.W))
  val fwdData   = Input(UInt(32.W))
}

// ---- Memory -> AXIStoreQueue 的外部读请求接口 ----
// 该请求只在 “SB + SQ 仍未 full cover” 时发起。
// needMask 记录真正仍需从 DRAM 获取的字节，便于 AXIStoreQueue 后续扩展更细粒度策略。
class AXISQLoadReqIO extends Bundle {
  val valid    = Output(Bool())
  val ready    = Input(Bool())
  val addr     = Output(UInt(32.W))
  val needMask = Output(UInt(4.W))
}

// ---- AXIStoreQueue -> Memory 的外部读响应接口 ----
class AXISQLoadRespIO extends Bundle {
  val valid = Input(Bool())
  val ready = Output(Bool())
  val rdata = Input(UInt(32.W))
}

// ---- AXIStoreQueue -> DRAM 的统一后端请求接口 ----
class AXISQMemReqIO extends Bundle {
  val valid   = Output(Bool())
  val ready   = Input(Bool())
  val isWrite = Output(Bool())
  val addr    = Output(UInt(32.W))
  val wdata   = Output(UInt(32.W))
  val wstrb   = Output(UInt(4.W))
}

// ---- DRAM -> AXIStoreQueue 的统一后端响应接口 ----
class AXISQMemRespIO extends Bundle {
  val valid = Input(Bool())
  val ready = Output(Bool())
  val rdata = Input(UInt(32.W))
}

// ---- AXIStoreQueue 调试输出接口 ----
// difftest 现在在 store 成功进入 AXIStoreQueue 的同拍进行比对，
// 因此该调试口直接反映 enqueue 握手成功时的原始 store 信息。
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
//   1. 接收来自 Commit 的 store enqueue，请求成功即视为 store 已提交；
//   2. 保存“已提交但尚未写回 DRAM”的 store，并对后续 load 提供 forwarding；
//   3. 统一仲裁 committed store 写回和 load miss 外读；
//   4. 保证任意时刻最多只有一笔 DRAM 请求在飞；
//   5. `commit_ram_*` 在 enqueue 成功同拍输出，使 difftest 直接对齐 store 的真实提交点。
// ============================================================================
class AXIStoreQueue(val depth: Int = CPUConfig.axiSqEntries) extends Module {
  private val idxWidth = log2Ceil(depth)
  private val ptrWidth = idxWidth + 1
  private val seqWidth = CPUConfig.storeSeqWidth
  private val storeBurstLimit = CPUConfig.axiSqStoreBurstLimit
  private val burstCntWidth = log2Ceil(storeBurstLimit + 1)

  val enq = IO(Flipped(new AXISQEnqIO))
  val query = IO(Flipped(new AXISQQueryIO))
  val loadReq = IO(Flipped(new AXISQLoadReqIO))
  val loadResp = IO(Flipped(new AXISQLoadRespIO))
  val memReq = IO(new AXISQMemReqIO)
  val memResp = IO(new AXISQMemRespIO)
  val debug = IO(new AXISQDebugIO)

  // 队列物理存储：按提交顺序入队，按最老顺序写回。
  val entries = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new AXISQEntry))))
  val head = RegInit(0.U(ptrWidth.W))
  val tail = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U((ptrWidth + 1).W))

  private def idx(ptr: UInt): UInt = ptr(idxWidth - 1, 0)
  private def seqOlderThan(a: UInt, b: UInt): Bool = (a - b)(seqWidth - 1)
  private def seqNewerOrEq(a: UInt, b: UInt): Bool = !(a - b)(seqWidth - 1)

  val queueFull = count === depth.U
  val hasStore = count =/= 0.U
  val headIdx = idx(head)
  val tailIdx = idx(tail)
  val headEntry = entries(headIdx)

  // ===================== Enqueue 接口 =====================
  enq.ready := !queueFull
  val enqFire = enq.valid && enq.ready

  when(enqFire) {
    entries(tailIdx).valid := true.B
    entries(tailIdx).addr := enq.addr
    entries(tailIdx).data := enq.data
    entries(tailIdx).mask := enq.mask
    entries(tailIdx).wordAddr := enq.wordAddr
    entries(tailIdx).wstrb := enq.wstrb
    entries(tailIdx).wdata := enq.wdata
    entries(tailIdx).storeSeq := enq.storeSeq
  }

  // ===================== Committed-store 查询 =====================
  // 对每个字节选择“对当前 load 可见的最年轻已提交 store”。
  val perByteFwdValid = Wire(Vec(4, Bool()))
  val perByteFwdData = Wire(Vec(4, UInt(8.W)))

  for (b <- 0 until 4) {
    val (bestValid, bestSeq, bestByte) = (0 until depth).foldLeft(
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

  // ===================== 统一 DRAM 读写仲裁 =====================
  val sIdle :: sLoadWaitResp :: sStoreWaitResp :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // 当 load miss 已经在等待，而 committed store 持续堆积时，限制连续 store 优先次数，避免 load 永久饿死。
  val consecutiveStoreWins = RegInit(0.U(burstCntWidth.W))
  val loadPending = loadReq.valid
  val forceLoad = loadPending && hasStore && (consecutiveStoreWins === storeBurstLimit.U)
  val chooseStore = hasStore && (!loadPending || !forceLoad)
  val chooseLoad = loadPending && (!hasStore || forceLoad)

  memReq.valid := false.B
  memReq.isWrite := false.B
  memReq.addr := 0.U
  memReq.wdata := 0.U
  memReq.wstrb := 0.U

  loadReq.ready := false.B
  loadResp.valid := false.B
  loadResp.rdata := 0.U
  memResp.ready := false.B

  val storeRespFire = WireInit(false.B)
  val loadRespFire = WireInit(false.B)

  switch(state) {
    is(sIdle) {
      when(chooseStore) {
        memReq.valid := true.B
        memReq.isWrite := true.B
        memReq.addr := Cat(headEntry.wordAddr, 0.U(2.W))
        memReq.wdata := headEntry.wdata
        memReq.wstrb := headEntry.wstrb
        when(memReq.valid && memReq.ready) {
          state := sStoreWaitResp
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
        memReq.valid := true.B
        memReq.isWrite := false.B
        memReq.addr := loadReq.addr
        loadReq.ready := memReq.ready
        when(loadReq.valid && loadReq.ready) {
          state := sLoadWaitResp
          consecutiveStoreWins := 0.U
        }
      }.otherwise {
        consecutiveStoreWins := 0.U
      }
    }

    is(sLoadWaitResp) {
      loadResp.valid := memResp.valid
      loadResp.rdata := memResp.rdata
      memResp.ready := loadResp.ready
      loadRespFire := loadResp.valid && loadResp.ready
      when(loadRespFire) {
        state := sIdle
      }
    }

    is(sStoreWaitResp) {
      // Store 写响应只供队列内部消费，因此这里可以直接 always-ready。
      memResp.ready := true.B
      storeRespFire := memResp.valid
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
  // 这里直接使用 enqueue 成功脉冲，而不是等待后端 DRAM 写响应：
  // 对当前架构而言，store 在成功进入 AXIStoreQueue 的同拍已经完成提交，
  // difftest 也已经支持在这个时刻完成 RAM 写事件比对。
  debug.commitRamWen := enqFire
  debug.commitRamWaddr := enq.addr
  debug.commitRamWdata := enq.data
  debug.commitRamWmask := enq.mask
}
