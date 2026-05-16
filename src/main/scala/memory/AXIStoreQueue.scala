package mycpu.memory

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// AXIStoreQueue 相关 Bundle 定义
// ============================================================================
class AXISQEntry extends Bundle {
  val valid    = Bool()
  val wordAddr = UInt(30.W)
  val wstrb    = UInt(4.W)
  val wdata    = UInt(32.W)
  val storeSeq = UInt(CPUConfig.storeSeqWidth.W)
}

// enq / loadReq / loadResp 三个握手通道的 Payload 定义。
class SQEnqPayload extends Bundle {
  val addr = UInt(32.W)
  val data = UInt(32.W)
  val mask = UInt(3.W)
  val wstrb    = UInt(4.W)
  val wdata    = UInt(32.W)
  val storeSeq = UInt(CPUConfig.storeSeqWidth.W)
}

class AXISQQueryIO extends Bundle {
  val valid     = Output(Bool())
  val wordAddr  = Output(UInt(30.W))
  val loadMask  = Output(UInt(4.W))
  val fullCover = Input(Bool())
  val fwdMask   = Input(UInt(4.W))
  val fwdData   = Input(UInt(32.W))
}

// 单 lane 的 debug 观测口（每拍一个 store enq 的视角）
class AXISQDebugLaneIO extends Bundle {
  val commitRamWen   = Output(Bool())
  val commitRamWaddr = Output(UInt(32.W))
  val commitRamWdata = Output(UInt(32.W))
  val commitRamWmask = Output(UInt(3.W))
}
// 多 lane 聚合 debug 口：与 enq Vec 长度一致；lane k 对应 enq(k).fire 的瞬时观测。
class AXISQDebugIO extends Bundle {
  val lane = Vec(mycpu.dataLane.LaneCapability.storeLanes.size, new AXISQDebugLaneIO)
}

// ============================================================================
// AXIStoreQueue —— 核外 committed store 队列 + 统一外部访存仲裁器
// （TD-A 升级版：Load 路径 outstanding=2，严格顺序返回；Store 仍单 outstanding）
// ============================================================================
// 本模块对外承担两类任务：
//   1. Committed store 队列：接收 Commit 入队的 store，向后续 Load 提供 forwarding，
//      并以 AW+W 同拍方式向 DRAM 写回。Store 写本身仍是单 outstanding（一次 AW+W
//      / B 串行）；
//   2. Load miss 外读：把 Memory 模块来的 Load 地址转换为 AR/R 事务发给 DRAM。
//      ★TD-A 关键：Load 路径升级为 outstanding=2★
//
// outstanding=2 的实现要点（参见 REF.md §2 / §6 版本 A）：
//   - 新增 loadInflightCnt（0 / 1 / 2），表示已经发出 AR 但尚未拿到 R 的 Load 数；
//   - loadAddr.ready 不再绑定状态机，而是 = (loadInflightCnt < 2)，与 DRAM 是否
//     正在执行其他事务、是否已发过 AR 都解耦；
//   - 每拍最多向下游 axi.ar 发 1 个 AR（DRAM 端口物理约束 1 op/cycle）；
//   - R 严格按 AR 入队顺序返回（依赖下游 DRAM_AXIInterface 的 readReqFifo /
//     readRespFifo 都是 FIFO 语义），因此本模块不需要自己做 ID 路由，只把 R
//     按"先来后到"直通给 loadData 即可；
//   - Store 仲裁为简化 MVP：当且仅当 loadInflightCnt=0 且无 pending load 时
//     才发起 store（避免读写穿插 + B 与 R 通道竞争）。普通 store 在后台 drain，
//     Load 优先（REF.md §5 推荐策略）。
//
// 不变量：
//   - 同一拍 axi.ar 与 axi.aw 不会同时 fire；
//   - 任意时刻 ≤ 2 个 outstanding read，≤ 1 个 outstanding write；
//   - R 通道返回顺序与 AR 发出顺序严格一致；
//   - commit_ram_* 仍在 enqueue 同拍输出，difftest 时序对齐不变。
// ============================================================================
class AXIStoreQueue(val depth: Int = CPUConfig.axiSqEntries) extends Module {
  private val idxWidth = log2Ceil(depth)
  private val ptrWidth = idxWidth + 1
  private val seqWidth = CPUConfig.storeSeqWidth
  private val storeBurstLimit = CPUConfig.axiSqStoreBurstLimit
  private val burstCntWidth = log2Ceil(storeBurstLimit + 1)

  // 与 myCPU 的握手接口（Phase A.2：enq 升级为 Vec(K)，K = storeLanes.size = 2）。
  // preserve_rob_lane_position 语义：enq(k) 严格对应 ROB.commit 的"本拍第 k 个 store"。
  private val K = mycpu.dataLane.LaneCapability.storeLanes.size
  val enq      = IO(Flipped(Vec(K, Decoupled(new SQEnqPayload))))
  // TD-B：query 升级为 Vec(N) 多查询口（loadLanes 大小，当前 N=2）。
  val query    = IO(Flipped(Vec(mycpu.dataLane.LaneCapability.loadLanes.size, new AXISQQueryIO)))
  val loadAddr = IO(Flipped(Decoupled(UInt(32.W))))
  val loadData = IO(Decoupled(UInt(32.W)))

  // 与 DRAM_AXIInterface 的 AXI 主设备接口
  val axi = IO(new AXIBundle())

  // 调试观测
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

  // ===================== Enqueue 接口（Phase A.2：Vec(K) 多端口紧凑写入）=====================
  // K = storeLanes.size = 2。每个端口对应 ROB.commit 第 k 个 store 的提交请求；
  // 不同端口同拍 fire 时，按 0→1 优先级紧凑写入 entries(tail) 与 entries(tail+1)。
  // ready 保守判定：count + k < depth（lane k 可被采纳所需的最小余量）。
  // 这样最坏情况下保留 K=2 个槽位即可避免溢出；对深度 16 的影响仅 1 项利用率。
  for (k <- 0 until K) {
    enq(k).ready := count + k.U < depth.U
  }
  val enqFireVec = VecInit((0 until K).map(k => enq(k).fire))
  val enqCount   = PopCount(enqFireVec)

  // 紧凑写入：把活跃 lane 按顺序映射到 tail+0 / tail+1
  // 对 K=2 共 4 种组合（00/01/10/11）逐一处理；其余 K 值需要更通用的 PriorityMux 逻辑。
  for (slot <- 0 until K) {
    // slot 是相对 tail 的偏移；lane=活跃端口中下标第 slot 个
    // 用 PriorityEncoder 序列从 enqFireVec 中挑出第 slot 个 1
    val maskFire = enqFireVec.asUInt
    // 把前 slot 个已选 lane 屏蔽掉
    val pickIdx = Wire(UInt(log2Ceil(K + 1).W))
    if (slot == 0) {
      pickIdx := PriorityEncoder(maskFire)
    } else {
      // K=2 时 slot=1 只需选第二个，等价于：若 lane0&&lane1 都 fire，则选 1；否则无效
      // 通用写法：依次剥离已选 OH。
      var maskRem = maskFire
      for (_ <- 0 until slot) {
        maskRem = maskRem & ~PriorityEncoderOH(maskRem)
      }
      pickIdx := PriorityEncoder(maskRem)
    }
    val slotActive = enqCount > slot.U
    val payloadVec = VecInit(enq.map(_.bits))
    val payload = payloadVec(pickIdx)
    val writeIdx = idx(tail + slot.U)
    when(slotActive) {
      entries(writeIdx).valid    := true.B
      entries(writeIdx).wordAddr := payload.addr(31, 2)
      entries(writeIdx).wstrb    := payload.wstrb
      entries(writeIdx).wdata    := payload.wdata
      entries(writeIdx).storeSeq := payload.storeSeq
    }
  }
  // ===================== Committed-store 查询（TD-B：Vec(N) 多查询口）=====================
  // AXIStoreQueue 的查询同样无状态，可任意复制。每个 Load lane 独立提供一份组合电路。
  for (q <- 0 until mycpu.dataLane.LaneCapability.loadLanes.size) {
    val perByteFwdValid = Wire(Vec(4, Bool()))
    val perByteFwdData = Wire(Vec(4, UInt(8.W)))

    for (b <- 0 until 4) {
      val (bestValid, _, bestByte) = (0 until depth).foldLeft(
        (false.B, 0.U(seqWidth.W), 0.U(8.W))
      ) { case ((hasBest, bestSeqSoFar, bestByteSoFar), i) =>
        val candidate = entries(i).valid &&
          (entries(i).wordAddr === query(q).wordAddr) &&
          entries(i).wstrb(b) &&
          query(q).loadMask(b)
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
    query(q).fwdMask := Mux(query(q).valid, rawQueryMask, 0.U)
    query(q).fwdData := Mux(
      query(q).valid,
      Cat(perByteFwdData(3), perByteFwdData(2), perByteFwdData(1), perByteFwdData(0)),
      0.U
    )
    query(q).fullCover := query(q).valid && ((rawQueryMask & query(q).loadMask) === query(q).loadMask)
  }

  // ============================================================================
  // TD-A：Load 路径 outstanding=2 状态跟踪
  // ----------------------------------------------------------------------------
  // 仅记录"已发出 AR 但 R 还未返回"的数量（最大 2）。无需保存 addr/上下文：
  //   - 上游 Memory 已经在自己的 MSHR Vec(2) 里维护 Load 上下文；
  //   - R 通道按 AR 入队顺序严格返回，由 DRAM_AXIInterface 内的 FIFO 保证。
  // 因此本模块只需做"流量控制"：保证不超过 2 个 outstanding。
  // ============================================================================
  val loadInflightCnt = RegInit(0.U(2.W))
  val arFireWire = WireInit(false.B)
  val rFireWire  = WireInit(false.B)

  // ============================================================================
  // Store 写状态机：保留单 outstanding（sStoreIdle / sStoreWaitB）
  // ----------------------------------------------------------------------------
  // Store 优先级最低：仅当 Load 路径完全空闲（loadInflightCnt=0 且无 pending load）
  // 时才发起 store。这样彻底避免读写穿插 + B 与 R 同拍竞争的复杂场景。
  // 仍然保留 storeBurstLimit 防止 store 持续阻塞 load 时的反向饥饿（理论上不会触发，
  // 但保留为防御）。
  // ============================================================================
  val sStoreIdle :: sStoreWaitB :: Nil = Enum(2)
  val storeState = RegInit(sStoreIdle)

  val consecutiveStoreWins = RegInit(0.U(burstCntWidth.W))
  val loadPending = loadAddr.valid

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

  // ============================================================================
  // Load 路径：AR 与 R 直通到 axi
  // ----------------------------------------------------------------------------
  // ar.valid 来源 = Memory 推上来的 loadAddr.valid + outstanding 上限未达；
  // ar.ready 来源 = 下游 DRAM_AXIInterface 的 readReqFifo 是否有空位；
  // loadAddr.ready 只在两者都满足时拉高（loadAddr 是 Decoupled，握手在两端同拍达成）。
  // ============================================================================
  val canIssueLoad = loadPending && (loadInflightCnt < 2.U) &&
                     (storeState === sStoreIdle)  // Store 占 DRAM 时让 Load 等一拍
  axi.ar.valid     := canIssueLoad
  axi.ar.bits.addr := loadAddr.bits
  axi.ar.bits.id   := 0.U
  loadAddr.ready   := canIssueLoad && axi.ar.ready
  arFireWire       := loadAddr.fire

  // R 通道直通：DRAM_AXIInterface 已经按 FIFO 顺序返回，本模块无需重排。
  loadData.valid := axi.r.valid
  loadData.bits  := axi.r.bits.data
  axi.r.ready    := loadData.ready
  rFireWire      := loadData.fire

  // outstanding 计数维护：AR fire +1，R fire -1；两者同拍则净变化 0。
  when(arFireWire && !rFireWire) {
    loadInflightCnt := loadInflightCnt + 1.U
  }.elsewhen(!arFireWire && rFireWire) {
    loadInflightCnt := loadInflightCnt - 1.U
  }

  // ============================================================================
  // Store 路径：仅在 Load 完全空闲时发起 AW+W 同拍握手
  // ============================================================================
  val storeRespFire = WireInit(false.B)
  // canIssueStore 严格条件：当前无 outstanding load 且本拍也没有新 load 入队尝试。
  // 这样 Store 只在 Load 真正"安静"的窗口里 drain，避免与 R 通道争用 DRAM。
  val canIssueStore = (storeState === sStoreIdle) && hasStore &&
                      (loadInflightCnt === 0.U) && !loadPending

  when(canIssueStore) {
    axi.aw.valid     := true.B
    axi.aw.bits.addr := Cat(headEntry.wordAddr, 0.U(2.W))
    axi.w.valid      := true.B
    axi.w.bits.data  := headEntry.wdata
    axi.w.bits.strb  := headEntry.wstrb
    when(axi.aw.ready && axi.w.ready) {
      storeState := sStoreWaitB
      consecutiveStoreWins := 0.U
    }
  }

  when(storeState === sStoreWaitB) {
    axi.b.ready := true.B
    storeRespFire := axi.b.valid
    when(storeRespFire) {
      storeState := sStoreIdle
    }
  }

  // ===================== 队列头释放 / 指针更新（Phase A.2：支持多 enq）=====================
  // 每拍：count 增加 enqCount（0..K），若 storeRespFire 则减 1；tail 前进 enqCount。
  when(storeRespFire) {
    entries(headIdx).valid := false.B
  }

  when(enqCount =/= 0.U) {
    tail := tail + enqCount
  }
  when(storeRespFire) {
    head := head + 1.U
  }
  // 实际取值保证 count + enqCount >= storeRespFire（storeRespFire 仅在 hasStore 时为真），
  // 故组合 count + enqCount - storeRespFire 不会下溢；Chisel 的位宽推断会自动加 1 位。
  count := count + enqCount - storeRespFire.asUInt

  // ===================== difftest 调试口（Phase A.2：Vec(K) 多 lane 视角）=====================
  // 每个 lane 独立输出对应 enq 端口的入队瞬时观测；上层 SoC_Top 按 ROB commit lane 映射回去。
  for (k <- 0 until K) {
    debug.lane(k).commitRamWen   := enq(k).fire
    debug.lane(k).commitRamWaddr := enq(k).bits.addr
    debug.lane(k).commitRamWdata := enq(k).bits.data
    debug.lane(k).commitRamWmask := enq(k).bits.mask
  }
}
