package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig
import mycpu.memory.AXISQQueryIO

// ============================================================================
// Memory（访存阶段）—— StoreBuffer / AXIStoreQueue / DRAM 三级访存前端
// ============================================================================
// 新版 load 处理顺序：
//   1. 先查 StoreBuffer（speculative store）；
//   2. 若无 olderUnknown，再按剩余 needMask 查 AXIStoreQueue（committed store）；
//   3. 若仍未 full cover，向 AXIStoreQueue 发起外部读请求；
//   4. 最终按优先级 StoreBuffer > AXIStoreQueue > DRAM 合并字节。
//
// 这样可以同时保证：
//   - 更老未知地址 store 仍会阻断 load；
//   - 已提交但未落地 DRAM 的 store 仍对后续 load 可见；
//   - 同一个 load 的不同字节可以分别来自 SB / SQ / DRAM。
//
// 阶段 η2（MSHR 非阻塞 Load）：
//   - 引入单项 MSHR（Miss Status Holding Register）：当 Load 必须发起 DRAM 外读时，
//     不再用 sWaitResp 把整条流水线冻结，而是把 Load 的 pdst/robIdx/funct3/offset/
//     已转发字节等保存到 MSHR；本拍 Memory.in 立即 fire（in.ready=true），后续
//     Store/ALU/Branch 可继续推进；
//   - 该 missed Load 的 lane0 在 Memory.out 中被设为 invalid（validMask(0)=0），
//     从而 Refresh 不会写 PRF / 不会标记 ROB done / 不会唤醒消费者；
//   - 当 sqLoadData 返回时，MSHR 合并最终结果；MyCPU 顶层用一个空闲的 Refresh 写
//     端口（写口仲裁）写 PRF + 标记 ROB.done + 置 ReadyTable + 广播 wakeup；
//   - 消费者由 Issue.scala 的第 4 个 hazard 源（MSHR-pending）阻塞，直到 MSHR
//     完成那拍 ack 触发后才放行；保证 RAW 安全；
//   - 分支误预测时若 MSHR 中的 Load 比误预测分支更年轻，标记 mshrFlushed，DRAM
//     响应回来后丢弃数据，不写 PRF。
// ============================================================================
class Memory extends Module {
  val in = IO(Flipped(Decoupled(new Execute_Memory_Payload)))
  val out = IO(Decoupled(new Memory_Refresh_Payload))

  // ---- StoreBuffer 接口 ----
  // 阶段 D'：sbWrite 升级为 Vec，使能 lane0 + lane1 同拍各写一条 Store；这是双 Mem
  // 口最关键的吞吐入口（同步将 storeLanes 写入 SB，不再受单端口仲裁）。
  // TD-B：sbQuery 升级为 Vec(loadLanes.size)，每个 Load lane 独立查询 SB（无状态查表）。
  val sbQuery = IO(Vec(LaneCapability.loadLanes.size, new SBQueryIO))
  val sbWrite = IO(Output(Vec(LaneCapability.storeLanes.size, new SBWriteIO)))

  // ---- AXIStoreQueue 前端接口 ----
  // committed-store 查询为纯组合接口；外部读请求/响应统一改为 Decoupled。
  // TD-B：sqQuery 升级为 Vec(loadLanes.size) 多口查询。
  val sqQuery    = IO(Vec(LaneCapability.loadLanes.size, new AXISQQueryIO))
  val sqLoadAddr  = IO(Decoupled(UInt(32.W)))
  val sqLoadData = IO(Flipped(Decoupled(UInt(32.W))))

  // ---- Memory 阶段重定向输出 ----
  val redirect = IO(new Bundle {
    val valid = Output(Bool())
    val addr = Output(UInt(32.W))
    val robIdx = Output(UInt(CPUConfig.robPtrWidth.W))
    val storeSeqSnap = Output(UInt(CPUConfig.storeSeqWidth.W))
    val checkpointIdx = Output(UInt(CPUConfig.ckptPtrWidth.W))
  })

  // ---- η2 / TD-D：MSHR 完成接口 Vec(2)（向 MyCPU 顶层发起 PRF/ROB/ReadyTable/wakeup 写口仲裁）----
  // TD-D 升级：从单端口 mshrComplete 升级为 Vec(2)，与 MSHR 槽位一一对应——
  //   mshrComplete(i) 直接映射到 MSHR slot i：当 slot i 已收到 DRAM 响应时 valid=1；
  //   MyCPU 顶层为每个 slot 独立寻找一条空闲 Refresh 写端口，独立 ack。
  // 这样若 2 个 Load outstanding 在同一拍 R 都返回，可以同拍并行回写 PRF / readyTb，
  // 减少 wakeup 链上的 1 拍延迟。
  val mshrComplete = IO(Vec(2, new Bundle {
    val valid          = Output(Bool())
    val pdst           = Output(UInt(CPUConfig.prfAddrWidth.W))
    val data           = Output(UInt(32.W))
    val robIdx         = Output(UInt(CPUConfig.robPtrWidth.W))
    val regWriteEnable = Output(Bool())
    val ack            = Input(Bool())
  }))

  // ---- η2/TD-005：MSHR 当前持有的 Load 信息（向 Issue 阶段提供 hazard #3/#4 的 pdst）----
  // TD-005 把 MSHR 扩成 Vec(2)，因此 mshrPending 也升级为 Vec(2)，由 Issue 同时检查两槽。
  val mshrPending = IO(Output(Vec(2, new Bundle {
    val valid          = Bool()
    val pdst           = UInt(CPUConfig.prfAddrWidth.W)
    val regWriteEnable = Bool()
  })))

  // ---- η2：分支误预测 flush 输入（用于 c 步：清掉年轻于分支的 MSHR 条目）----
  val flushIn = IO(Input(new Bundle {
    val valid        = Bool()
    val branchRobIdx = UInt(CPUConfig.robPtrWidth.W)
  }))

  // ---- η2/TD-B：本拍是否新捕获 Load 进入 MSHR（供 MyCPU γ-wake 抑制使用）----
  // TD-B：升级为 Vec(loadLanes.size)，per-lane 报告"哪个 Load lane 在本拍被捕获"，
  // 让 MyCPU 顶层逐 lane 抑制 γ-wake（避免误抑制非 Load lane 的正常唤醒）。
  val mshrCaptureFire = IO(Output(Vec(LaneCapability.loadLanes.size, Bool())))

  // ============================================================================
  // 通用辅助函数（不依赖 lane 角色）
  // ============================================================================
  private def decodeStoreByteMask(funct3: UInt, addr: UInt): UInt = {
    val func3OH = UIntToOH(funct3(1, 0))
    val offset  = addr(1, 0)
    Mux1H(Seq(
      func3OH(0) -> (1.U(4.W) << offset),
      func3OH(1) -> Mux(offset(1), "b1100".U(4.W), "b0011".U(4.W)),
      func3OH(2) -> "b1111".U(4.W),
      func3OH(3) -> 0.U(4.W)
    ))
  }
  private def decodeStoreByteData(funct3: UInt, addr: UInt, raw: UInt): UInt = {
    val func3OH = UIntToOH(funct3(1, 0))
    val offset  = addr(1, 0)
    Mux1H(Seq(
      func3OH(0) -> (raw(7, 0) << (offset << 3.U)),
      func3OH(1) -> Mux(offset(1), Cat(raw(15, 0), 0.U(16.W)), Cat(0.U(16.W), raw(15, 0))),
      func3OH(2) -> raw,
      func3OH(3) -> 0.U(32.W)
    ))
  }
  private def decodeLoadByteMask(funct3: UInt, addr: UInt): UInt = {
    val func3OH = UIntToOH(funct3(1, 0))
    val offset  = addr(1, 0)
    Mux1H(Seq(
      func3OH(0) -> (1.U(4.W) << offset),
      func3OH(1) -> Mux(offset(1), "b1100".U(4.W), "b0011".U(4.W)),
      func3OH(2) -> "b1111".U(4.W),
      func3OH(3) -> 0.U(4.W)
    ))
  }
  private def mergeLoadSources(
    sbMask: UInt, sbData: UInt,
    sqMask: UInt, sqData: UInt,
    dramData: UInt
  ): UInt = {
    val mergedBytes = Wire(Vec(4, UInt(8.W)))
    for (b <- 0 until 4) {
      mergedBytes(b) := Mux(
        sbMask(b),
        sbData(b * 8 + 7, b * 8),
        Mux(sqMask(b), sqData(b * 8 + 7, b * 8), dramData(b * 8 + 7, b * 8))
      )
    }
    Cat(mergedBytes(3), mergedBytes(2), mergedBytes(1), mergedBytes(0))
  }
  private def signExtendLoad(mergedWord: UInt, f3: UInt, off: UInt): UInt = {
    val byte = (mergedWord >> (off << 3.U))(7, 0)
    val half = Mux(off(1), mergedWord(31, 16), mergedWord(15, 0))
    MuxLookup(f3, mergedWord)(Seq(
      "b000".U -> Cat(Fill(24, byte(7)), byte),
      "b001".U -> Cat(Fill(16, half(15)), half),
      "b010".U -> mergedWord,
      "b100".U -> Cat(0.U(24.W), byte),
      "b101".U -> Cat(0.U(16.W), half)
    ))
  }
  private def isYoungerRob(a: UInt, b: UInt): Bool = {
    val w = a.getWidth
    ((a - b)(w - 1) === 0.U) && (a =/= b)
  }

  // ============================================================================
  // lane0 信号 + 早期 mispredict kill（不被 memStall 门控）
  // ============================================================================
  val inL0  = in.bits.lanes(0)
  val outL0 = out.bits.lanes(0)
  out.bits := DontCare

  val td0     = inL0.type_decode_together
  val uType_0 = LaneCapability.isUType(td0)
  val jal_0   = LaneCapability.isJal(td0)
  val jalr_0  = LaneCapability.isJalr(td0)
  val bType_0 = LaneCapability.isBType(td0)
  val lType_0 = LaneCapability.isLType(td0)
  val iType_0 = LaneCapability.isIType(td0)
  val sType_0 = LaneCapability.isSType(td0)
  val rType_0 = LaneCapability.isRType(td0)

  val lane0Valid           = in.valid && in.bits.validMask(0)

  // TD-C：mispredict 仲裁——支持任意 branchLane 触发 mispredict。
  // 双发射 dispatch 保证 lane0 < lane1 程序序，故 PriorityEncoder 选最低 idx 即"最老"。
  // Issue.scala 强制 no-double-Branch，理论上同拍至多 1 个 lane 的 mispredict 为真。
  private val branchLanesSeq = LaneCapability.branchLaneSet.toSeq.sorted
  val mispredictPerLane = VecInit((0 until CPUConfig.memoryWidth).map { k =>
    if (LaneCapability.branchLaneSet.contains(k)) {
      val laneValidK = in.valid && in.bits.validMask(k)
      laneValidK && in.bits.lanes(k).mispredict
    } else false.B
  })
  val anyMispredictEarly = mispredictPerLane.asUInt.orR
  val mispredictIdx      = PriorityEncoder(mispredictPerLane.asUInt)
  val winnerLane         = in.bits.lanes(mispredictIdx)
  // 兼容：lane0MispredictEarly 仅在 winnerLane==0 时为真，等价于旧逻辑。
  val lane0MispredictEarly = anyMispredictEarly && (mispredictIdx === 0.U)
  // 通用：lane k 是否被"更老的 mispredict"早 kill（k > mispredictIdx）。
  def killByOlderMispredictEarly(k: Int): Bool =
    anyMispredictEarly && (mispredictIdx < k.U)

  // ============================================================================
  // StoreBuffer 写口（per storeLane）
  // ============================================================================
  for ((laneIdx, sIdx) <- LaneCapability.storeLanes.zipWithIndex) {
    val inLk       = in.bits.lanes(laneIdx)
    val tdK        = inLk.type_decode_together
    val sTypeK     = LaneCapability.isSType(tdK)
    val laneValidK = in.valid && in.bits.validMask(laneIdx)
    val killByLane0 = killByOlderMispredictEarly(laneIdx)
    sbWrite(sIdx).valid    := laneValidK && sTypeK && inLk.isSbAlloc && !killByLane0
    sbWrite(sIdx).idx      := inLk.sbIdx
    sbWrite(sIdx).addr     := inLk.data
    sbWrite(sIdx).data     := inLk.reg_rdata2
    sbWrite(sIdx).mask     := inLk.inst_funct3
    sbWrite(sIdx).byteMask := decodeStoreByteMask(inLk.inst_funct3, inLk.data)
    sbWrite(sIdx).byteData := decodeStoreByteData(inLk.inst_funct3, inLk.data, inLk.reg_rdata2)
  }

  // ============================================================================
  // 每个 Load lane：本地 decode + sbQuery / sqQuery 端口绑定 + fullCover 计算
  // ----------------------------------------------------------------------------
  // 1. sbQuery/sqQuery 是无状态查表，可任意复制；每个 Load lane 独立查询；
  // 2. lane>0 受 lane0MispredictEarly 早期 kill：lane0 mispredict 时不允许 lane1
  //    发起 SB/SQ 查询、不参与 MSHR 分配；
  // 3. Issue.scala 已禁止"两条 Load 同对发射"，理论上同拍最多 1 个 Load lane
  //    wantsActive=1；这里 PriorityEncoder 选最低位以保险。
  // ============================================================================
  val loadLaneCnt       = LaneCapability.loadLanes.size
  val loadLaneAddr      = Wire(Vec(loadLaneCnt, UInt(32.W)))
  val loadLaneFunct3    = Wire(Vec(loadLaneCnt, UInt(3.W)))
  val loadLaneOffset    = Wire(Vec(loadLaneCnt, UInt(2.W)))
  val loadLanePdst      = Wire(Vec(loadLaneCnt, UInt(CPUConfig.prfAddrWidth.W)))
  val loadLaneRobIdx    = Wire(Vec(loadLaneCnt, UInt(CPUConfig.robPtrWidth.W)))
  val loadLaneRegWen    = Wire(Vec(loadLaneCnt, Bool()))
  val loadLaneSbFwdMask = Wire(Vec(loadLaneCnt, UInt(4.W)))
  val loadLaneSbFwdData = Wire(Vec(loadLaneCnt, UInt(32.W)))
  val loadLaneSqVisMask = Wire(Vec(loadLaneCnt, UInt(4.W)))
  val loadLaneSqVisData = Wire(Vec(loadLaneCnt, UInt(32.W)))
  val loadLaneNeedAfter = Wire(Vec(loadLaneCnt, UInt(4.W)))
  val loadLaneOlderUnk  = Wire(Vec(loadLaneCnt, Bool()))
  val loadLaneFullCov   = Wire(Vec(loadLaneCnt, UInt(32.W)))
  val loadLaneWants     = Wire(Vec(loadLaneCnt, Bool()))

  for ((laneIdx, qIdx) <- LaneCapability.loadLanes.zipWithIndex) {
    val inLk       = in.bits.lanes(laneIdx)
    val tdK        = inLk.type_decode_together
    val laneValidK = in.valid && in.bits.validMask(laneIdx)
    val lTypeK     = LaneCapability.isLType(tdK)
    val addrK      = inLk.data
    val offsetK    = addrK(1, 0)
    val funct3K    = inLk.inst_funct3
    val loadByteMaskK = decodeLoadByteMask(funct3K, addrK)
    val killByLane0   = killByOlderMispredictEarly(laneIdx)
    val wantsK        = laneValidK && lTypeK && !killByLane0

    sbQuery(qIdx).valid        := wantsK
    sbQuery(qIdx).wordAddr     := addrK(31, 2)
    sbQuery(qIdx).loadMask     := loadByteMaskK
    sbQuery(qIdx).storeSeqSnap := inLk.storeSeqSnap

    val sbFwdMaskK    = sbQuery(qIdx).fwdMask & loadByteMaskK
    val olderUnknownK = sbQuery(qIdx).olderUnknown

    sqQuery(qIdx).valid    := wantsK && !olderUnknownK
    sqQuery(qIdx).wordAddr := addrK(31, 2)
    sqQuery(qIdx).loadMask := loadByteMaskK

    val sqRawMaskK     = sqQuery(qIdx).fwdMask & loadByteMaskK
    val sqVisibleMaskK = sqRawMaskK & ~sbFwdMaskK
    val sqVisibleDataK = sqQuery(qIdx).fwdData

    val needAfterSQK = Mux(wantsK,
      loadByteMaskK & ~(sbFwdMaskK | sqVisibleMaskK),
      0.U(4.W))

    val mergedFullK = mergeLoadSources(
      sbFwdMaskK, sbQuery(qIdx).fwdData,
      sqVisibleMaskK, sqVisibleDataK,
      0.U(32.W)
    )
    val fullCoverResultK = signExtendLoad(mergedFullK, funct3K, offsetK)

    loadLaneAddr(qIdx)      := addrK
    loadLaneFunct3(qIdx)    := funct3K
    loadLaneOffset(qIdx)    := offsetK
    loadLanePdst(qIdx)      := inLk.pdst
    loadLaneRobIdx(qIdx)    := inLk.robIdx
    loadLaneRegWen(qIdx)    := inLk.regWriteEnable
    loadLaneSbFwdMask(qIdx) := sbFwdMaskK
    loadLaneSbFwdData(qIdx) := sbQuery(qIdx).fwdData
    loadLaneSqVisMask(qIdx) := sqVisibleMaskK
    loadLaneSqVisData(qIdx) := sqVisibleDataK
    loadLaneNeedAfter(qIdx) := needAfterSQK
    loadLaneOlderUnk(qIdx)  := olderUnknownK
    loadLaneFullCov(qIdx)   := fullCoverResultK
    loadLaneWants(qIdx)     := wantsK
  }

  val anyActiveLoad = loadLaneWants.asUInt.orR
  val activeIdx     = PriorityEncoder(loadLaneWants.asUInt)

  val actAddr      = loadLaneAddr(activeIdx)
  val actFunct3    = loadLaneFunct3(activeIdx)
  val actOffset    = loadLaneOffset(activeIdx)
  val actPdst      = loadLanePdst(activeIdx)
  val actRobIdx    = loadLaneRobIdx(activeIdx)
  val actRegWen    = loadLaneRegWen(activeIdx)
  val actSbMask    = loadLaneSbFwdMask(activeIdx)
  val actSbData    = loadLaneSbFwdData(activeIdx)
  val actSqMask    = loadLaneSqVisMask(activeIdx)
  val actSqData    = loadLaneSqVisData(activeIdx)
  val actNeedAfter = loadLaneNeedAfter(activeIdx)
  val actOlderUnk  = loadLaneOlderUnk(activeIdx)
  val actFullCov   = loadLaneFullCov(activeIdx)

  // ============================================================================
  // MSHR Vec(2) + AR 单端口 + memStall（继承 TD-005/TD-A 设计）
  // TD-B 主要改动：所有 capture/saveContext 用 actXxx；mshrCaptureFire 输出为 Vec
  // （仅活动 lane 那位为 1，让 MyCPU 顶层逐 lane 抑制 γ-wake）。
  // ============================================================================
  val numMshr = 2
  val mshrValid          = RegInit(VecInit(Seq.fill(numMshr)(false.B)))
  val mshrResultValid    = RegInit(VecInit(Seq.fill(numMshr)(false.B)))
  val mshrFlushed        = RegInit(VecInit(Seq.fill(numMshr)(false.B)))
  val mshrArPending      = RegInit(VecInit(Seq.fill(numMshr)(false.B)))
  val mshrPdst           = Reg(Vec(numMshr, UInt(CPUConfig.prfAddrWidth.W)))
  val mshrRobIdx         = Reg(Vec(numMshr, UInt(CPUConfig.robPtrWidth.W)))
  val mshrRegWriteEnable = Reg(Vec(numMshr, Bool()))
  val mshrFunct3         = Reg(Vec(numMshr, UInt(3.W)))
  val mshrOffset         = Reg(Vec(numMshr, UInt(2.W)))
  val mshrSavedSbMask    = Reg(Vec(numMshr, UInt(4.W)))
  val mshrSavedSbData    = Reg(Vec(numMshr, UInt(32.W)))
  val mshrSavedSqMask    = Reg(Vec(numMshr, UInt(4.W)))
  val mshrSavedSqData    = Reg(Vec(numMshr, UInt(32.W)))
  val mshrResultData     = Reg(Vec(numMshr, UInt(32.W)))
  val mshrSavedAddr      = Reg(Vec(numMshr, UInt(30.W)))

  val mshrInFlightFifo  = Module(new Queue(UInt(log2Ceil(numMshr).W), entries = numMshr, pipe = true))
  val mshrInFlightValid = mshrInFlightFifo.io.deq.valid
  val mshrInFlightSlot  = mshrInFlightFifo.io.deq.bits

  val needDram = anyActiveLoad && !actOlderUnk && (actNeedAfter =/= 0.U)

  // ============================================================================
  // TD-E-3：per-lane "需要发起 DRAM 读" 与 "需要被本 lane 流向 stall" 信号
  // ----------------------------------------------------------------------------
  // 每个 Load lane 独立判定：
  //   - laneNeedDram(q)  ：该 lane 想 capture 进 MSHR（wants && !olderUnk && needAfter≠0）
  //   - laneOlderUnkAct(q)：该 lane 因更老未知地址 store 而须整拍 stall（wants && olderUnk）
  //
  // 注：alloc 同拍若 flushIn 蕴含本 lane 比 branchRobIdx 年轻，本可加 flushKillNew
  // 防御；但 MyCPU 顶层把 flushIn 直接接到 Memory.redirect（同拍），用 flushIn 反推
  // memStall 会形成组合环（flushIn → memStall → redirect → flushIn）。
  // 实际 architecture 已自洽：
  //   1) 同 bundle 更老 lane 的 mispredict 由 killByLane0 在 wantsK 处直接 kill；
  //   2) 跨 cycle 已 alloc 的 younger MSHR 由 mshrShouldFlushI（仅写 reg，无 comb 路径）
  //      在分支 redirect 后续拍清掉；
  //   3) 上游 redirect 把 younger 的 ROB entry 一起 squash，PRF 不会被脏写回。
  // 故此处不再引入 flushKillNew。
  // ============================================================================
  val laneNeedDram = VecInit((0 until loadLaneCnt).map(q =>
    loadLaneWants(q) && !loadLaneOlderUnk(q) && (loadLaneNeedAfter(q) =/= 0.U)
  ))
  val laneOlderUnkAct = VecInit((0 until loadLaneCnt).map(q =>
    loadLaneWants(q) && loadLaneOlderUnk(q)
  ))
  val anyOlderUnk = laneOlderUnkAct.asUInt.orR
  val laneNeedDramEffective = laneNeedDram
  val numNeedDram = PopCount(laneNeedDramEffective.asUInt)

  // ---- TD-D：每个 MSHR slot 独立驱动 mshrComplete(i) ----
  // 完成条件：slot i 已分配（mshrValid）、未被 flush、且 R 已回（mshrResultValid）。
  // ackSlot/ackAny 改为 per-slot 的 ackVec/ackAny（沿用其它逻辑的复用变量名以最小化改动）。
  val ackVec = VecInit((0 until numMshr).map(i =>
    mshrValid(i) && !mshrFlushed(i) && mshrResultValid(i)
  ))
  for (i <- 0 until numMshr) {
    mshrComplete(i).valid          := ackVec(i)
    mshrComplete(i).pdst           := mshrPdst(i)
    mshrComplete(i).data           := mshrResultData(i)
    mshrComplete(i).robIdx         := mshrRobIdx(i)
    mshrComplete(i).regWriteEnable := mshrRegWriteEnable(i)
  }
  // per-slot 是否本拍被 ack（由 MyCPU 顶层拉低）
  val ackFireVec = VecInit((0 until numMshr).map(i => mshrComplete(i).ack))
  val ackAny     = ackFireVec.asUInt.orR
  // 兼容旧名（用于 alloc 路径选择 isAckSlot 的判定）：保留 ackSlot 作 PriorityEncoder（仅
  // 在"alloc 重用 ack 槽位"时使用，且新分配走"任意先 ack 的 slot"，故这里只取最低位）。
  val ackSlot    = PriorityEncoder(ackFireVec.asUInt)

  val freeMask    = VecInit((0 until numMshr).map(i =>
    !mshrValid(i) || ackFireVec(i)
  ))
  val mshrAnyFree = freeMask.asUInt.orR
  val freeCnt     = PopCount(freeMask.asUInt)
  // TD-E-3：第一/第二空闲 slot（用于双 alloc）。注意 numMshr=2，故 freeIdx1 仅在
  // 双 capture 场景下有意义，且 PopCount(freeMask)>=2 才进入双 alloc 分支。
  val allocSlot   = PriorityEncoder(freeMask.asUInt)
  val freeMaskAfterFirst = freeMask.asUInt & ~UIntToOH(allocSlot)
  val allocSlot1 = PriorityEncoder(freeMaskAfterFirst)

  // TD-E-3：per-lane 分配的 slot 编号
  //   - lane0 总是拿 freeIdx0（最低空闲 slot）
  //   - lane1 若 lane0 也 capture 则拿 freeIdx1，否则拿 freeIdx0
  val captureSlotForLane = Wire(Vec(loadLaneCnt, UInt(log2Ceil(numMshr).W)))
  captureSlotForLane(0) := allocSlot
  if (loadLaneCnt > 1) {
    captureSlotForLane(1) := Mux(laneNeedDramEffective(0), allocSlot1, allocSlot)
  }

  // ============================================================================
  // TD-E-3：双 capture / per-lane AR 仲裁 / all-or-none accept rule
  // ----------------------------------------------------------------------------
  // capture 决策：
  //   canCaptureAll：MSHR 空闲数 ≥ 本拍欲 capture 的 lane 数。这是 all-or-none：
  //     若不满足，本拍 0 个 lane 被 capture，整 bundle stall（保 bundle 原子性）。
  //   captureAcceptedVec(q)：lane q 本拍是否被 MSHR 接管（写入 mshrValid + 上下文）。
  //
  // AR 仲裁（最多 1 op/cycle）：
  //   优先级：launchPendingAR > 本拍 fresh capture 中"程序序最老者"。
  //   - lane 程序序与 qIdx 单调一致（lane0 < lane1）。
  //   - freshFireLane：在 captureAcceptedVec 中选最低位 q。
  //   - 其余 fresh capture 的 lane 设 mshrArPending=true，下一拍由 launchPendingAR 补发。
  // ============================================================================
  val enableArPending: Boolean = true
  val arPendingVec    = mshrArPending
  val anyArPending    = if (enableArPending) arPendingVec.asUInt.orR else false.B
  val pendingArSlot   = PriorityEncoder(arPendingVec.asUInt)
  val axiFreeThisCycle = mshrInFlightFifo.io.enq.ready

  val canCaptureAll = (numNeedDram === 0.U) || (freeCnt >= numNeedDram)
  val captureAcceptedVec = VecInit((0 until loadLaneCnt).map(q =>
    laneNeedDramEffective(q) && canCaptureAll
  ))
  val anyCaptureAccepted = captureAcceptedVec.asUInt.orR
  val freshFireLane      = PriorityEncoder(captureAcceptedVec.asUInt)
  val launchPendingAR    = anyArPending && axiFreeThisCycle
  val launchFreshAR      = anyCaptureAccepted && axiFreeThisCycle && !anyArPending
  // fresh AR 槽位 = 该 freshFireLane 在本拍分配到的 MSHR slot
  val freshArSlot        = captureSlotForLane(freshFireLane)
  val arLaunchSlot       = Mux(launchPendingAR, pendingArSlot, freshArSlot)

  sqLoadAddr.valid := launchPendingAR || launchFreshAR
  sqLoadAddr.bits  := Mux(launchPendingAR,
    Cat(mshrSavedAddr(pendingArSlot), 0.U(2.W)),
    Cat(loadLaneAddr(freshFireLane)(31, 2), 0.U(2.W)))

  val inflightResultValid = mshrInFlightValid && mshrResultValid(mshrInFlightSlot)
  val inflightFlushed     = mshrInFlightValid && mshrFlushed(mshrInFlightSlot)
  sqLoadData.ready := mshrInFlightValid && (!inflightResultValid || inflightFlushed)

  val ifs = mshrInFlightSlot
  val freshlyMergedWord = mergeLoadSources(
    mshrSavedSbMask(ifs), mshrSavedSbData(ifs),
    mshrSavedSqMask(ifs), mshrSavedSqData(ifs),
    sqLoadData.bits
  )
  val freshlyComputedResult = signExtendLoad(freshlyMergedWord, mshrFunct3(ifs), mshrOffset(ifs))

  for (i <- 0 until numMshr) {
    mshrPending(i).valid          := mshrValid(i) && !ackFireVec(i)
    mshrPending(i).pdst           := mshrPdst(i)
    mshrPending(i).regWriteEnable := mshrRegWriteEnable(i)
  }

  // TD-E-3：mshrCaptureFire 升级为 per-lane 真"capture accepted"。该信号供 MyCPU
  // 顶层抑制 γ-wake，并被 outMaskBits 用于把 lane 输出从 PRF 写回中扣除。
  val mshrCaptureFireWire = anyCaptureAccepted
  for (q <- 0 until loadLaneCnt) {
    mshrCaptureFire(q) := captureAcceptedVec(q)
  }

  // ============================================================================
  // TD-E-3：memStall 改为 per-lane all-or-none 语义
  //   - 任一 lane older-unknown → 整拍 stall（与 v18 行为一致，保守安全）
  //   - 任一 lane 需要 capture 但 freeCnt 不足 → 整拍 stall（all-or-none）
  //   - 否则 in.ready=1，bundle 通过；fullCover lane 直接写、capture lane 走 MSHR、
  //     非 Load lane passthrough。
  // ============================================================================
  val memStall = WireInit(false.B)
  when(anyOlderUnk) {
    memStall := true.B
  }.elsewhen(anyCaptureAccepted) {
    memStall := false.B
  }.elsewhen(numNeedDram =/= 0.U) {
    // 有 lane 想 capture 但 canCaptureAll=false（free 不足）
    memStall := true.B
  }

  val freshArFiredThisCycle   = launchFreshAR && sqLoadAddr.fire
  val pendingArFiredThisCycle = launchPendingAR && sqLoadAddr.fire

  // ============================================================================
  // TD-E-3：每个 MSHR slot 的更新分配循环
  // ----------------------------------------------------------------------------
  // 关键变化：alloc 不再唯一对应 activeIdx，而是查找"哪个 lane 的 captureSlotForLane(q)
  // 等于本 slot i 且 captureAcceptedVec(q)=1"。loadLaneCnt=2 + numMshr=2 时同拍
  // 至多 1 lane 命中本 slot；assertion 由 I-1 / I-3 / I-5 守护（详见末尾）。
  // ============================================================================
  for (i <- 0 until numMshr) {
    // 本拍占用 slot i 的 lane（at most 1，由 captureSlotForLane 互斥保证）
    val laneFiresThisSlot = VecInit((0 until loadLaneCnt).map(q =>
      captureAcceptedVec(q) && (captureSlotForLane(q) === i.U)
    ))
    val isAllocSlot = laneFiresThisSlot.asUInt.orR
    val allocLane   = PriorityEncoder(laneFiresThisSlot.asUInt)
    // 本 slot 是否同拍 fresh AR fire（用于决定 mshrArPending 初值）
    val isFreshFireSlot   = freshArFiredThisCycle && (freshArSlot === i.U)
    val isInFlightSlot    = mshrInFlightValid && mshrInFlightSlot === i.U
    val isPendingFireSlot = pendingArFiredThisCycle && pendingArSlot === i.U
    val isAckSlot         = ackFireVec(i)
    val mshrShouldFlushI  = flushIn.valid && mshrValid(i) && isYoungerRob(mshrRobIdx(i), flushIn.branchRobIdx)

    // 用于 alloc 写入的 per-slot 上下文（来自命中本 slot 的 lane）
    val ctxAddr   = loadLaneAddr(allocLane)
    val ctxPdst   = loadLanePdst(allocLane)
    val ctxRobIdx = loadLaneRobIdx(allocLane)
    val ctxRegWen = loadLaneRegWen(allocLane)
    val ctxF3     = loadLaneFunct3(allocLane)
    val ctxOff    = loadLaneOffset(allocLane)
    val ctxSbM    = loadLaneSbFwdMask(allocLane)
    val ctxSbD    = loadLaneSbFwdData(allocLane)
    val ctxSqM    = loadLaneSqVisMask(allocLane)
    val ctxSqD    = loadLaneSqVisData(allocLane)

    when(isAckSlot) {
      mshrValid(i)       := false.B
      mshrResultValid(i) := false.B
      mshrFlushed(i)     := false.B
      mshrArPending(i)   := false.B
      when(isAllocSlot) {
        mshrValid(i)          := true.B
        mshrPdst(i)           := ctxPdst
        mshrRobIdx(i)         := ctxRobIdx
        mshrRegWriteEnable(i) := ctxRegWen
        mshrFunct3(i)         := ctxF3
        mshrOffset(i)         := ctxOff
        mshrSavedSbMask(i)    := ctxSbM
        mshrSavedSbData(i)    := ctxSbD
        mshrSavedSqMask(i)    := ctxSqM
        mshrSavedSqData(i)    := ctxSqD
        mshrSavedAddr(i)      := ctxAddr(31, 2)
        mshrArPending(i)      := !isFreshFireSlot
      }
    }.elsewhen(isInFlightSlot && sqLoadData.fire && mshrFlushed(i)) {
      mshrValid(i)       := false.B
      mshrResultValid(i) := false.B
      mshrFlushed(i)     := false.B
      mshrArPending(i)   := false.B
    }.elsewhen(isInFlightSlot && sqLoadData.fire && !mshrFlushed(i)) {
      when(mshrShouldFlushI) {
        mshrValid(i)       := false.B
        mshrResultValid(i) := false.B
        mshrFlushed(i)     := false.B
        mshrArPending(i)   := false.B
      }.otherwise {
        mshrResultValid(i) := true.B
        mshrResultData(i)  := freshlyComputedResult
      }
    }.elsewhen(isAllocSlot) {
      mshrValid(i)          := true.B
      mshrPdst(i)           := ctxPdst
      mshrRobIdx(i)         := ctxRobIdx
      mshrRegWriteEnable(i) := ctxRegWen
      mshrFunct3(i)         := ctxF3
      mshrOffset(i)         := ctxOff
      mshrSavedSbMask(i)    := ctxSbM
      mshrSavedSbData(i)    := ctxSbD
      mshrSavedSqMask(i)    := ctxSqM
      mshrSavedSqData(i)    := ctxSqD
      mshrSavedAddr(i)      := ctxAddr(31, 2)
      mshrResultValid(i)    := false.B
      mshrFlushed(i)        := false.B
      mshrArPending(i)      := !isFreshFireSlot
    }.elsewhen(isPendingFireSlot) {
      mshrArPending(i) := false.B
    }.elsewhen(mshrShouldFlushI) {
      when(mshrArPending(i)) {
        mshrValid(i)       := false.B
        mshrResultValid(i) := false.B
        mshrFlushed(i)     := false.B
        mshrArPending(i)   := false.B
      }.elsewhen(mshrResultValid(i)) {
        mshrValid(i)       := false.B
        mshrResultValid(i) := false.B
        mshrFlushed(i)     := false.B
        mshrArPending(i)   := false.B
      }.otherwise {
        mshrFlushed(i) := true.B
      }
    }
  }

  mshrInFlightFifo.io.enq.valid := sqLoadAddr.fire
  mshrInFlightFifo.io.enq.bits  := arLaunchSlot
  mshrInFlightFifo.io.deq.ready := sqLoadData.fire

  in.ready  := !memStall
  out.valid := in.valid && !memStall

  redirect.valid         := anyMispredictEarly && !memStall
  redirect.addr          := winnerLane.actual_target
  redirect.robIdx        := winnerLane.robIdx
  redirect.storeSeqSnap  := winnerLane.storeSeqSnap
  redirect.checkpointIdx := winnerLane.checkpointIdx
  val lane0MispredictKill = redirect.valid

  // 输出 lanes：默认 passthrough，再按指令类型/活动 Load 覆盖
  for (k <- 0 until CPUConfig.memoryWidth) {
    val inLk  = in.bits.lanes(k)
    val outLk = out.bits.lanes(k)
    outLk.pdst           := inLk.pdst
    outLk.data           := inLk.data
    outLk.robIdx         := inLk.robIdx
    outLk.regWriteEnable := inLk.regWriteEnable
  }

  outL0.pdst := Mux(uType_0 || jal_0 || jalr_0 || lType_0 || iType_0 || rType_0, inL0.pdst, 0.U)
  when(bType_0 || sType_0) {
    outL0.data := 0.U(32.W)
  }
  for (k <- 1 until CPUConfig.memoryWidth) {
    val inLk  = in.bits.lanes(k)
    val tdK   = inLk.type_decode_together
    val sTypeK = LaneCapability.isSType(tdK)
    when(sTypeK) {
      out.bits.lanes(k).data := 0.U(32.W)
      out.bits.lanes(k).pdst := 0.U
    }
  }
  // ============================================================================
  // TD-E-1：per-lane 输出 data 覆盖（取代旧的 activeLoadResult 单写）
  // ----------------------------------------------------------------------------
  // 每个 Load lane 独立判定是否写 fullCover 结果：
  //   - wantsK：该 lane 本拍是有效 Load；
  //   - !olderUnkK：没有更老 SB 未知地址阻断（否则整拍 stall）；
  //   - needAftK === 0：sbFwd + sqFwd 已完整覆盖，无需 DRAM；
  // 当前阶段 Issue.scala 仍强制 single-Load-per-cycle（doubleLoadStall 未解除），
  // 故同拍至多 1 个 lane 真正写。TD-E-3 解锁双 capture 后此处天然支持 lane0/lane1
  // 各自写 fullCover。"miss 走 MSHR" 的 lane：data 字段保留 inLk passthrough，
  // 但 outMaskBits 把它清 0，下游不会消费。
  // ============================================================================
  for ((laneIdx, qIdx) <- LaneCapability.loadLanes.zipWithIndex) {
    val wantsK        = loadLaneWants(qIdx)
    val needAftK      = loadLaneNeedAfter(qIdx)
    val olderUnkK     = loadLaneOlderUnk(qIdx)
    val fullCovWriteK = wantsK && !olderUnkK && (needAftK === 0.U)
    when(fullCovWriteK) {
      out.bits.lanes(laneIdx).data := loadLaneFullCov(qIdx)
    }
  }

  val outMaskBits = Wire(Vec(CPUConfig.memoryWidth, Bool()))
  for (k <- 0 until CPUConfig.memoryWidth) {
    val isThisLaneCapture =
      if (LaneCapability.loadLanes.contains(k)) {
        val qIdx = LaneCapability.loadLanes.indexOf(k)
        // TD-E-3：改为 per-lane captureAcceptedVec(qIdx)。该 lane 已被 MSHR 接管，
        // 输出位扣除以避免下游 Refresh 把"地址当数据"写回 PRF；MSHR 完成后由
        // mshrComplete(slot) 走专用写口写回。
        captureAcceptedVec(qIdx)
      } else {
        false.B
      }
    // TD-C：只 kill 比 winner 更年轻的 lane（k > mispredictIdx）。
    // winner 自身（含 Branch lane）需正常输出以让 ROB 完成该 Branch；
    // memStall 期间 redirect.valid=0，但 outMaskBits 仍被 kill 以避免 stall 期 SB/SQ 后效。
    val killByOlder = if (k == 0) false.B else (anyMispredictEarly && (mispredictIdx < k.U))
    outMaskBits(k) := in.bits.validMask(k) && !isThisLaneCapture && !killByOlder
  }
  out.bits.validMask := outMaskBits.asUInt

  // ============================================================================
  // TD-E-0：阶段 0 不变量断言（不改功能；为后续 per-lane / 双 capture 改造铺路）
  // ----------------------------------------------------------------------------
  // 这些断言锁定 v18 现状的关键不变量。一旦后续阶段引入 bug，断言会在仿真期立刻触发，
  // 而不是在最终 mismatch 才暴露。
  //
  // 不变量列表：
  //   I-1（bundle 原子性 / fresh capture）：
  //     `mshrCaptureFireWire` 仅在本拍 in/out 握手通过时才允许为 1；当 memStall=1 时，
  //     in.ready=0、out.valid=0，禁止任何 fresh MSHR alloc / fresh AR fire / FIFO enq，
  //     否则下一拍同一 bundle 重放会重复分配 MSHR / 重复发 AR。
  //
  //   I-2（AR 单端口 + FIFO 1:1）：
  //     sqLoadAddr.fire 必须伴随 mshrInFlightFifo.io.enq.fire；FIFO 满时严禁发 AR。
  //
  //   I-3（fresh 与 pending AR 互斥）：
  //     同一拍最多 1 条 AR fire（DRAM 物理 1 op/cycle），launchFreshAR && launchPendingAR
  //     不允许同真。
  //
  //   I-4（pending slot 合法）：
  //     mshrArPending(i)=1 蕴含 mshrValid(i)=1（未失效）。结果未到时不应已经 ack。
  //
  //   I-5（capture 语义 / TD-E-2 已重写）：
  //     新语义下 mshrCaptureFireWire = newLoadCanCapture（= needDram && mshrAnyFree），
  //     与 AR 是否本拍 fire 解耦。capture 真即意味着本拍 alloc 写入 MSHR slot，AR
  //     若因 AXI 端口忙或 pending 抢先而未 fire，则该 slot 设 mshrArPending=1。
  //     断言形式从"capture==freshAR.fire"放宽为"capture蕴含slot真为free"。
  //
  //   I-6（in.ready / out.valid 一致）：
  //     out.valid := in.valid && !memStall；与 in.ready := !memStall 保持镜像。
  //
  //   I-7（actOlderUnk 必 stall）：
  //     anyActiveLoad && actOlderUnk 时 memStall 必须为 1（保证更老 SB 未知地址不放 Load 过）。
  //
  // 备注：Chisel `assert` 在 verilator 仿真期生效；综合时被忽略，无 RTL 面积代价。
  // ============================================================================

  // I-1：fresh capture 必须发生在 in.ready=1（即 !memStall）的拍上。
  assert(!mshrCaptureFireWire || in.ready,
    "[TD-E-0/I-1] capture fired while in.ready=0 (bundle non-atomic)")

  // I-2：每个 sqLoadAddr.fire 必须对应一次 mshrInFlightFifo enq。
  assert(!sqLoadAddr.fire || mshrInFlightFifo.io.enq.ready,
    "[TD-E-0/I-2] sqLoadAddr.fire while mshrInFlightFifo full")
  assert(sqLoadAddr.fire === mshrInFlightFifo.io.enq.valid,
    "[TD-E-0/I-2] sqLoadAddr.fire vs mshrInFlightFifo.enq.valid mismatch")

  // I-3：fresh 与 pending AR 不同时发起。
  assert(!(launchFreshAR && launchPendingAR),
    "[TD-E-0/I-3] launchFreshAR and launchPendingAR both high (single AR/cycle)")

  // I-4：pending slot 合法性（mshrArPending=1 时 mshrValid 必须=1）。
  for (i <- 0 until numMshr) {
    assert(!mshrArPending(i) || mshrValid(i),
      s"[TD-E-0/I-4] mshrArPending($i)=1 but mshrValid($i)=0")
  }

  // I-5（TD-E-3 重写）：capture 蕴含 all-or-none 满足且至少一 lane 实际接受；
  // 同时新建定义恒等式 capture==anyCaptureAccepted；并校验 freshFireLane 当前真捕获。
  assert(mshrCaptureFireWire === anyCaptureAccepted,
    "[TD-E-0/I-5] mshrCaptureFireWire != anyCaptureAccepted (definition drift)")
  assert(!anyCaptureAccepted || canCaptureAll,
    "[TD-E-0/I-5] capture accepted but canCaptureAll=0 (all-or-none broken)")
  assert(!launchFreshAR || captureAcceptedVec(freshFireLane),
    "[TD-E-0/I-5] launchFreshAR but freshFireLane not in captureAcceptedVec")

  // TD-E-3 新增 I-8：双 alloc 时两 lane 的 captureSlotForLane 必须互斥
  if (loadLaneCnt > 1) {
    val bothCap = captureAcceptedVec(0) && captureAcceptedVec(1)
    assert(!bothCap || (captureSlotForLane(0) =/= captureSlotForLane(1)),
      "[TD-E-0/I-8] dual capture but slot conflict")
  }
  // I-9：fresh AR 不允许在 anyArPending=1 时发起（pending 必须先发）
  assert(!(launchFreshAR && anyArPending),
    "[TD-E-0/I-9] launchFreshAR while anyArPending=1 (priority broken)")

  // I-6：in.ready / out.valid 镜像。
  assert(in.ready === !memStall,
    "[TD-E-0/I-6] in.ready != !memStall")

  // I-7：anyOlderUnk 必导致 memStall（TD-E-3：从 actOlderUnk 改为 anyOlderUnk）。
  when(anyOlderUnk) {
    assert(memStall, "[TD-E-0/I-7] anyOlderUnk=1 but memStall=0")
  }
}
