package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// StoreBuffer（存储缓冲区）—— 仅管理核内 speculative store 生命周期
// ============================================================================
// 改造后的职责边界：
//   1. 继续负责 Store 槽位分配、storeSeq 生成、rollback 精确清理；
//   2. 继续为 Load 提供“只看更老 speculative store”的字节级 forwarding；
//   3. 不再维护 committed/drain 状态，不再直接向 DRAM 发写请求；
//   4. ROB head 是 Store 时，仅按 storeSeq 把候选表项内容提供给 Commit 路径；
//   5. 只有在 AXIStoreQueue enqueue 成功的同拍，才允许释放该表项。
// ============================================================================
class StoreBuffer(val depth: Int = CPUConfig.sbEntries) extends Module {
  private val idxWidth = CPUConfig.sbIdxWidth
  private val seqWidth = CPUConfig.storeSeqWidth

  // ---- 分配接口（Dispatch 阶段）----
  val alloc = IO(Flipped(new SBAllocIO))

  // ---- Store 写入接口（Memory 阶段；阶段 D' 升级为 Vec：lane0 + lane1 同拍各写一条）----
  val write = IO(Input(Vec(LaneCapability.storeLanes.size, new SBWriteIO)))

  // ---- Load 查询接口（Memory 阶段；TD-B 升级为 Vec：lane0 / lane1 同拍各发起一次查询）----
  val query = IO(Flipped(Vec(LaneCapability.loadLanes.size, new SBQueryIO)))

  // ---- 回滚接口（Memory redirect）----
  val rollback = IO(Flipped(new SBRollbackIO))

  // ---- Commit 候选接口（ROB head store -> AXIStoreQueue）----
  val commit = IO(Flipped(new SBCommitIO))

  // 存储阵列：所有仍处于 speculative 状态的 store 都保存在这里。
  val buffer = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new StoreBufferEntry))))

  // 空槽统计：只看 valid 位即可，因为 committed store 一旦成功进入 AXIStoreQueue 就会立刻从 SB 释放。
  val freeVec = VecInit(buffer.map(entry => !entry.valid))
  val freeCount = PopCount(freeVec)

  // ---- FreeList 式的“前四个空槽”选择逻辑 ----
  val freeIdxs = Wire(Vec(CPUConfig.renameWidth, UInt(idxWidth.W)))
  val mask0 = freeVec.asUInt
  freeIdxs(0) := PriorityEncoder(mask0)
  val mask1 = mask0 & ~PriorityEncoderOH(mask0)
  freeIdxs(1) := PriorityEncoder(mask1)
  val mask2 = mask1 & ~PriorityEncoderOH(mask1)
  freeIdxs(2) := PriorityEncoder(mask2)
  val mask3 = mask2 & ~PriorityEncoderOH(mask2)
  freeIdxs(3) := PriorityEncoder(mask3)

  // 循环 storeSeq 比较辅助函数。约束没有变化：活跃 store 的真实距离远小于半区entries.U间，因此可以沿用“减法最高位”判定新旧。
  private def seqOlderThan(a: UInt, b: UInt): Bool = (a - b)(seqWidth - 1)
  private def seqNewerOrEq(a: UInt, b: UInt): Bool = !(a - b)(seqWidth - 1)
  val nextStoreSeq = RegInit(0.U(seqWidth.W)) // nextStoreSeq：Dispatch 每实际分配 N 个 store，就向前推进 N。

  // ===================== Dispatch 分配逻辑 =====================
  // 将当前空闲槽位数直接透传给 Dispatch，由 Dispatch 根据 Rename 阶段的 storeCount 自行判断是否足够。
  // 这样 SB 内部的 canAlloc 不依赖 alloc.request，从根本上消除与 Dispatch 之间的组合环路，
  // 同时也让 Dispatch 侧统一用 doDispatch 门控 request，避免 SB 在 ROB/IQ 资源不足时提前消耗槽位。
  alloc.availCount   := freeCount
  alloc.nextStoreSeq := nextStoreSeq
  alloc.idxs         := freeIdxs // 直接将 4 个空闲的槽位透传，由 Dispatch 自行选择取几个
  for (i <- 0 until CPUConfig.renameWidth) { alloc.storeSeqs(i) := nextStoreSeq + i.U }

  // Dispatch 已保证仅在真正派发成功时才把 alloc.request 抬起来，所以这里无需再二次判断 canAlloc。
  val doAlloc = alloc.request > 0.U && !rollback.valid
  when(doAlloc) {
    for (i <- 0 until CPUConfig.renameWidth) {
      when(i.U < alloc.request) {
        val physIdx = freeIdxs(i)
        buffer(physIdx).valid := true.B
        buffer(physIdx).addrValid := false.B
        buffer(physIdx).addr := 0.U
        buffer(physIdx).data := 0.U
        buffer(physIdx).mask := 0.U
        buffer(physIdx).byteMask := 0.U
        buffer(physIdx).byteData := 0.U
        buffer(physIdx).storeSeq := nextStoreSeq + i.U
      }
    }
    nextStoreSeq := nextStoreSeq + alloc.request
  }

  // ===================== Store 写入逻辑（阶段 D'：双口） =====================
  // Store 到达 Memory 阶段后，地址/数据/字节掩码在这里落入 SB。
  // 多端口语义：Dispatch 已为每条 Store 分配独立的 sbIdx（FreeList 逐项），因此 lane0
  // 与 lane1 在同拍 sbWrite 永远写不同槽位，互不干扰；这里直接顺序遍历端口即可。
  for (p <- 0 until LaneCapability.storeLanes.size) {
    when(write(p).valid) {
      buffer(write(p).idx).addrValid := true.B
      buffer(write(p).idx).addr := write(p).addr
      buffer(write(p).idx).data := write(p).data
      buffer(write(p).idx).mask := write(p).mask
      buffer(write(p).idx).byteMask := write(p).byteMask
      buffer(write(p).idx).byteData := write(p).byteData
    }
  }

  // ===================== Load 查询逻辑（TD-B 升级：Vec(N) 多查询口）=====================
  // 这里只看"更老且仍在 SB 中"的 speculative store。
  // 一旦 store 成功进入 AXIStoreQueue，它就不再属于 SB 的可见范围。
  //
  // TD-B：每个 Load lane 独立提供一份组合查询逻辑（StoreBuffer 是无状态查表，可任意复制）。
  // 不同 lane 的查询彼此完全独立——每个 lane 有自己的 storeSeqSnap / wordAddr / loadMask。
  for (q <- 0 until LaneCapability.loadLanes.size) {
    val olderVec = Wire(Vec(depth, Bool()))
    val wordAddrHit = Wire(Vec(depth, Bool()))
    val pendingVec = Wire(Vec(depth, Bool()))
    for (i <- 0 until depth) {
      val entry = buffer(i)
      val isOlder = entry.valid && seqOlderThan(entry.storeSeq, query(q).storeSeqSnap)
      olderVec(i) := isOlder
      wordAddrHit(i) := isOlder && entry.addrValid && (entry.addr(31, 2) === query(q).wordAddr)
      pendingVec(i) := isOlder && !entry.addrValid
    }

    // 只要存在更老但地址未知的 store，就必须先停顿，不能跳过 SB 去看后面的 committed queue/DRAM。
    query(q).olderUnknown := query(q).valid && pendingVec.asUInt.orR

    val perByteFwdValid = Wire(Vec(4, Bool()))
    val perByteFwdData = Wire(Vec(4, UInt(8.W)))

    for (b <- 0 until 4) {
      val (bestValid, _, bestByte) = (0 until depth).foldLeft(
        (false.B, 0.U(seqWidth.W), 0.U(8.W))
      ) { case ((hasBest, bestSeqSoFar, bestByteSoFar), i) =>
        val candidate = wordAddrHit(i) && buffer(i).byteMask(b)
        val chooseThis = candidate && (!hasBest || seqNewerOrEq(buffer(i).storeSeq, bestSeqSoFar))
        (
          hasBest || candidate,
          Mux(chooseThis, buffer(i).storeSeq, bestSeqSoFar),
          Mux(chooseThis, buffer(i).byteData(b * 8 + 7, b * 8), bestByteSoFar)
        )
      }
      perByteFwdValid(b) := bestValid
      perByteFwdData(b) := bestByte
    }

    query(q).fwdMask := perByteFwdValid.asUInt
    query(q).fwdData := Cat(perByteFwdData(3), perByteFwdData(2), perByteFwdData(1), perByteFwdData(0))
    query(q).fullCover := query(q).valid && ((query(q).fwdMask & query(q).loadMask) === query(q).loadMask)
  }

  // ===================== Commit 候选查询 =====================
  // ROB head 是 Store 时，通过 storeSeq 在 SB 中精确找到唯一候选。
  // 该候选只有在地址/数据已经写好时才允许 enqueue；否则必须继续阻塞 commit。
  val commitMatchVec = Wire(Vec(depth, Bool()))
  for (i <- 0 until depth) {
    commitMatchVec(i) := commit.valid && buffer(i).valid && (buffer(i).storeSeq === commit.storeSeq)
  }

  val commitHit = commitMatchVec.asUInt.orR
  val commitIdx = PriorityEncoder(commitMatchVec.asUInt)
  val commitEntry = Wire(new StoreBufferEntry)
  commitEntry := 0.U.asTypeOf(new StoreBufferEntry)
  when(commitHit) {
    commitEntry := buffer(commitIdx)
  }

  commit.entryValid := commitHit && commitEntry.addrValid
  commit.addr := commitEntry.addr
  commit.data := commitEntry.data
  commit.mask := commitEntry.mask
  // commit.wordAddr 已不再被任何下游消费（AXIStoreQueue 自行从 addr 派生），故移除该冗余输出。
  commit.wstrb := commitEntry.byteMask
  commit.wdata := commitEntry.byteData

  // 只有 enqueue 握手成功的同拍，才允许释放 SB 表项。
  when(commit.valid && commit.enqSuccess && commitHit) {
    buffer(commitIdx).valid := false.B
  }

  // ===================== 精确回滚逻辑 =====================
  // 新架构下，SB 中剩下的全部都是“尚未成功进入 AXIStoreQueue 的 speculative store”，
  // 因此 rollback 直接按 storeSeqSnap 清除年轻表项即可。
  when(rollback.valid) {
    nextStoreSeq := rollback.storeSeqSnap
    for (i <- 0 until depth) {
      when(buffer(i).valid && seqNewerOrEq(buffer(i).storeSeq, rollback.storeSeqSnap)) {
        buffer(i).valid := false.B
      }
    }
  }
}
