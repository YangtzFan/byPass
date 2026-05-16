package mycpu.device

import chisel3._
import chisel3.util._
import mycpu.CPUConfig
import mycpu.dataLane._

// ============================================================================
// ROB（重排序缓冲区）—— 支持 4-wide 分配 + Memory 阶段回滚
// ============================================================================
// 变更：
//   - 分配接口由 Dispatch 阶段驱动（原由 Rename 驱动）
//   - 完成标记接口改为 ROBRefreshIO（Refresh 阶段标记完成）
//   - 移除 Store 相关字段（Store 信息由 StoreBuffer 管理）
//   - Commit 接口不再输出 Store 地址/数据/掩码
// ============================================================================
class ROB(val entries: Int = CPUConfig.robEntries) extends Module {
  val idxW = CPUConfig.robIdxWidth  // 索引位宽（7）

  val alloc    = IO(Flipped(new ROBMultiAllocIO)) // Dispatch 4-wide 分配接口
  val rollback = IO(new Bundle {                  // Memory 阶段回滚接口
    val valid  = Input(Bool())                        // 回滚使能
    val robIdx = Input(UInt(CPUConfig.robPtrWidth.W)) // 误预测指令的 ROB 指针
  })
  val refresh = IO(Vec(CPUConfig.refreshWidth, Flipped(new ROBRefreshIO)))  // Refresh 阶段完成标记接口（每 lane 一个）
  val commit  = IO(Vec(CPUConfig.commitWidth, new ROBCommitIO))             // 提交接口（每 lane 一个）
  val commitCount = IO(Output(UInt(log2Ceil(CPUConfig.commitWidth + 1).W))) // 本拍实际提交条数（0..commitWidth）
  // 输出 head 指针和 count，供 MyCPU 判定某个 refresh 是否落在当前 ROB 活跃区间内
  // 用于屏蔽"选择性 flush 泄漏"的年轻 lane 对 PRF/ReadyTable/BCT 的错误写入
  val headPtr = IO(Output(UInt((idxW + 1).W)))
  val countOut = IO(Output(UInt((idxW + 1).W)))

  // ---- Store drain 阻塞接口（Phase A.2：升级为 Vec(K)，K = storeLanes.size = 2）----
  // commitBlocked(m) = 本拍第 m 个 store 候选无法 enqueue（AXISQ 反压或 SB 候选未就绪）。
  // 当 m 路被阻塞时：该 store 所在 ROB lane 及其后所有 lane 在本拍均不允许 commit。
  val commitBlocked = IO(Input(Vec(LaneCapability.storeLanes.size, Bool())))
  // 本拍每个 ROB commit lane 的"store 序号"输出。当该 lane 是 store 且本拍提交时，
  // commitStoreOrdinal(i) ∈ [0, K) 表明该 store 应去找 sqEnq(ordinal) 入队；非 store 时为 0。
  val commitStoreOrdinal = IO(Output(Vec(CPUConfig.commitWidth, UInt(log2Ceil(LaneCapability.storeLanes.size + 1).W))))
  // 暴露 head 每个 lane 的 store 标志与 storeSeq，供 MyCPU 选择本拍的多个 store 候选。
  val headIsStoreVec = IO(Output(Vec(CPUConfig.commitWidth, Bool())))
  val headStoreSeqVec = IO(Output(Vec(CPUConfig.commitWidth, UInt(CPUConfig.storeSeqWidth.W))))

  // 存储阵列和指针
  val rob = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new ROBEntry))))
  val head = RegInit(0.U((idxW + 1).W))   // 头指针（最老的指令）
  val tail = RegInit(0.U((idxW + 1).W))   // 尾指针（下一个空位）
  private def idx(ptr: UInt) = ptr(idxW - 1, 0)

  val count = tail - head
  val empty = count === 0.U
  val full  = count === entries.U

  alloc.availCount := entries.U - count // 输出空闲个数由 Dispatch 判断
  for (i <- 0 until 4) { // 返回连续的 ROB 指针（从当前 tail 开始）
    alloc.idxs(i) := tail + i.U
  }

  val doAlloc = alloc.request > 0.U && !rollback.valid // 判断是否真正进行分配（request > 0 且无回滚）

  // ===================== 提交输出（组合逻辑，始终驱动）=====================
  // commitMask 链（Phase A.2 升级版）：
  //   1. headLaneReadyHere(i) = 与 commitBlocked 解耦的提交候选条件（不含 AXISQ 反压）
  //      = notEmpty && done && !sysStopBefore && !tooManyStores && prevOk
  //      其中 tooManyStores 限制"本拍前 i lane 中已成为 commit 候选的 store 总数 ≤ K"。
  //   2. canCommitVec(i) = headLaneReadyHere(i) && !axisqBlocked_for_this_lane(i)
  //      axisqBlocked_for_this_lane(i) = headEntries(i).isStore && commitBlocked(ordinal_i)
  //      ordinal_i 是该 lane 在"前 i lane 中已通过 headLaneReady 的 store 数"——
  //      即"本拍第 ordinal_i 个 store 候选"，用以匹配 sqEnq(ordinal_i)。
  //   3. commitStoreOrdinal(i) 输出该 lane 对应的 store 序号；非 store 时输出 0。
  val commitW = CPUConfig.commitWidth
  val K = LaneCapability.storeLanes.size
  val headEntries = VecInit((0 until commitW).map { i => rob(idx(head + i.U)) })
  val canCommitVec = Wire(Vec(commitW, Bool()))
  val headLaneReadyHere = Wire(Vec(commitW, Bool()))
  val storeOrdinalVec = Wire(Vec(commitW, UInt(log2Ceil(K + 1).W)))

  for (i <- 0 until commitW) {
    val notEmpty_i  = count > i.U
    val done_i      = headEntries(i).done

    // SYSTEM/ECALL 截断：若更老的某 lane 是 sysStop，则本拍其后的 lane 一律不提交。
    val sysStopBefore = if (i == 0) false.B else
      VecInit((0 until i).map(j => headEntries(j).isSysStop)).asUInt.orR

    // 本拍前 i lane 中已成为候选（headLaneReadyHere(j)==true）的 store 数。
    // 用于：a) tooManyStores 截断；b) ordinal 编号。
    val storeOrdinalHere: UInt = if (i == 0) 0.U else
      PopCount(VecInit((0 until i).map(j => headEntries(j).isStore && headLaneReadyHere(j))))
    val tooManyStores = headEntries(i).isStore && storeOrdinalHere >= K.U

    val prevOk = if (i == 0) true.B else headLaneReadyHere(i - 1)
    headLaneReadyHere(i) := prevOk && notEmpty_i && done_i && !sysStopBefore && !tooManyStores

    // 该 lane 对应"本拍第 storeOrdinalHere 个 store"；非 store lane 输出 0。
    storeOrdinalVec(i) := Mux(headEntries(i).isStore, storeOrdinalHere, 0.U)

    // AXISQ 反压：若该 lane 是 store 且对应序号的 commitBlocked 拉起，则本 lane 不能 commit。
    // storeOrdinalHere 取值范围 [0, K]；当达到 K 时 tooManyStores=true 已使 headLaneReadyHere=false，
    // 此处用 Mux 兜底防止 Vec 越界（虽然 Chisel 越界会折叠到最高位、但显式更安全）。
    val safeOrd = Mux(storeOrdinalHere < K.U, storeOrdinalHere, 0.U)
    // 顺序提交链：lane i 的 canCommit 必须串联前一 lane 的 canCommit。
    // 若 lane i-1 因 AXISQ 反压未提交，younger lane i 也必须停下，避免乱序提交。
    val prevCommitOk = if (i == 0) true.B else canCommitVec(i - 1)
    canCommitVec(i) := prevCommitOk && headLaneReadyHere(i) &&
      !(headEntries(i).isStore && commitBlocked(safeOrd))
  }

  commitStoreOrdinal := storeOrdinalVec
  for (i <- 0 until commitW) {
    headIsStoreVec(i)  := headLaneReadyHere(i) && headEntries(i).isStore
    headStoreSeqVec(i) := headEntries(i).storeSeq
  }

  for (i <- 0 until commitW) {
    val e = headEntries(i)
    val c = commit(i)
    c.valid         := canCommitVec(i)
    c.pc            := e.pc
    c.rd            := e.rd
    c.regWen        := e.regWen && canCommitVec(i)
    c.regWBData     := e.regWBData
    c.isStore       := e.isStore
    c.storeSeq      := e.storeSeq
    c.hasCheckpoint := e.hasCheckpoint
    c.pdst          := e.pdst
    c.stalePdst     := e.stalePdst
    c.ldst          := e.ldst
  }
  val commitNum = PopCount(canCommitVec)
  commitCount := commitNum
  headPtr  := head
  countOut := count

  // ===================== Memory 阶段回滚逻辑 + Refresh 完成标记 =====================
  // 先做 rollback / Refresh，再做 alloc（最后赋值优先）。这样可保证当同一 ROB 表项
  // 在同一拍既被 Refresh（来自 wrong-path / 选择性 flush 泄漏 / squash 同帧到达的
  // MSHR 完成）打 done=1 又被新指令 dispatch 占用时，alloc 的 done:=false 一定胜出，
  // 避免 stale done 持续到 commit 错误数据（参见 lw 双 capture bug 根因）。
  when(rollback.valid) {
    tail := rollback.robIdx + 1.U
  }
  // 多 lane Refresh：每 lane 独立更新自己的 ROB 表项。
  // 活跃区间保护：仅当 robIdx 落在 [head, tail)（即 (idx - head) < count）时才允许
  // 写入 done/regWBData。否则该 refresh 来自被 rollback 释放的旧条目，可能已被新指令
  // 重占；若不加保护会把 stale done=1 + 旧数据写入新 entry。
  for (r <- 0 until CPUConfig.refreshWidth) {
    when(refresh(r).valid) {
      val inRange  = (refresh(r).idx - head) < count
      val refEntry = rob(idx(refresh(r).idx))
      // 双重保护：
      //   1. inRange  — 屏蔽 rollback 之后已被释放的旧条目（stale robIdx）
      //   2. !refEntry.done — 屏蔽对同一 ROB 条目的"二次 Refresh"覆盖
      //      典型场景：load 被 IQ 错误地二次 issue（携带错误地址/数据），其 MSHR 结果
      //      晚于第一次正确完成到达（此时 done=1），若不加保护会用错误数据覆盖正确结果。
      when(inRange && !refEntry.done) {
        refEntry.done         := true.B
        refEntry.regWBData    := refresh(r).regWBData
      }
    }
  }
  // ===================== 4-wide 分配逻辑 =====================
  // 放在 Refresh 之后：若同拍 Refresh 与 alloc 落到同一 idx（典型场景：上一轮 wrong-path
  // 的 mshrComplete 刚 ack，紧接着 rollback+re-dispatch 把该 idx 重新分配给新指令），
  // alloc 的 done:=false 必须胜出。Chisel 末赋值优先，故此处放在 Refresh 之后。
  when(!rollback.valid && doAlloc) {
    for (i <- 0 until 4) {
      when(i.U < alloc.request) { // FetchBuffer 确保有效指令优先占有低位
        val entry = rob(idx(tail + i.U)) // 分配下一表项
        entry.done          := false.B
        entry.pc            := alloc.data(i).pc
        entry.rd            := alloc.data(i).rd
        entry.regWen        := alloc.data(i).regWen
        entry.regWBData     := 0.U
        entry.isStore       := alloc.data(i).isStore
        entry.isSysStop     := alloc.data(i).isSysStop  // ECALL/EBREAK/FENCE：commit 时截断后续 lane
        entry.storeSeq      := alloc.data(i).storeSeq  // 写入 Store 的逻辑年龄（Commit 时传给 StoreBuffer 用于定位表项）
        entry.hasCheckpoint := alloc.data(i).hasCheckpoint  // 标记该指令是否保存了 BCT checkpoint
        // 物理寄存器映射信息存入 ROB 表项
        entry.pdst          := alloc.data(i).pdst
        entry.stalePdst     := alloc.data(i).stalePdst
        entry.ldst          := alloc.data(i).ldst
      }
    }
    tail := tail + alloc.request
  }
  // ===================== Commit 指针更新 =====================
  when(commitNum =/= 0.U) {
    head := head + commitNum
  }
}
