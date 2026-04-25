package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// IssueQueue（发射队列）—— Vec + FreeList + instSeq 架构
// ============================================================================
// 参照 StoreBuffer 的设计，将原先的环形缓冲区改为：
//   1. 物理槽位用 Vec[depth] 平坦数组存储，不再依赖 head/tail 指针
//   2. FreeList（空闲列表）：位向量管理可分配的物理槽位索引
//   3. instSeq（指令逻辑年龄）：nextInstSeq 每分配 N 条指令就 +N
//      - 使用“循环序号 + 减法最高位”的比较方式判定新旧（与 StoreBuffer 的 storeSeq 完全一致），
//        以确保在任意长仿真时间下即使 nextInstSeq 自然回绕也能得到正确的年龄关系；
//      - 只要活跃条目数（≤ depth = IQ 容量）远小于序号空间的半区间，比较即正确；
//      - 每个表项记录自己的 instSeq，与物理位置完全解耦，物理槽可以乱序分配和释放
//   4. Issue 阶段选择 instSeq 最小（最老）的有效条目进行发射
//   5. 精确回滚：Memory 重定向时清除所有有效条目（IQ 中的指令均为未提交的投机指令）
//
// 核心作用：解耦 4-wide Dispatch 与 Issue/Execute 后端之间的带宽差异
//           承担原 RenameBuffer 的缓冲职责
//           物理槽位乱序管理，为后续乱序选择发射做准备
// ============================================================================

// ---- IssueQueue 内部表项：在 DispatchEntry 基础上增加 instSeq 字段 ----
// 阶段 1：每个条目额外维护 src1Ready / src2Ready，用于 ready-based 发射选择。
class IssueQueueEntry extends Bundle {
  val data       = new DispatchEntry // 原始 Dispatch 阶段的指令信息
  val instSeq    = UInt(CPUConfig.instSeqWidth.W) // 指令逻辑年龄（循环序号）
  val src1Ready  = Bool() // 源 1 是否已就绪（入队时由 ReadyTable 初始化，之后由 wakeup 翻 1）
  val src2Ready  = Bool() // 源 2 是否已就绪
}

// ---- IssueQueue 分配接口（Dispatch 阶段使用）----
// IQ 仅对外暴露 availCount；Dispatch 用它与 Rename 的 validCount 比较。
// 不再携带 request 字段：IQ 内部根据 write.valid / write.validCount 自行判断，
// 避免 IQ 与 Dispatch 之间出现组合环路。
class IQAllocIO extends Bundle {
  val availCount = Input(UInt(log2Ceil(CPUConfig.issueQueueEntries + 1).W)) // 当前空闲槽位数
}

class IQWriteData extends Bundle {
  val entries    = Vec(4, new DispatchEntry)
  val validCount = UInt(3.W)
  val valid      = Bool() // 本周期是否有有效的 Dispatch 输出
}

class IssueQueue(val depth: Int = CPUConfig.issueQueueEntries) extends Module {
  private val idxWidth = log2Ceil(depth)
  private val seqWidth = CPUConfig.instSeqWidth

  // ---- 分配接口（Dispatch 阶段使用）----
  val alloc = IO(Flipped(new IQAllocIO))

  // ---- 写入接口（Dispatch 阶段使用：直接写入表项数据）----
  // 与 StoreBuffer 不同，IssueQueue 在分配时就写入全部数据（Dispatch 已有完整信息）
  val write = IO(Input(new IQWriteData))

  // ---- 发射选择接口（Issue 阶段使用）----
  // issueWidth 路并行发射：每路给 Issue 一个独立的 Decoupled 通道。
  //   lane0 = oldest-ready（所有候选中 instSeq 最老）
  //   lane1 = 次老 ready（剔除 lane0 之后再选一次 oldest-ready），并且仅限 ALU 型指令
  // 释放时谁 ready 就释放谁（可各自独立）。
  val issue = IO(Vec(CPUConfig.issueWidth, Decoupled(new DispatchEntry)))

  // ---- 清空信号（Memory 重定向时使用）----
  val flush = IO(Input(Bool()))
  // OoO 场景下需要按 robIdx 精确清除年轻条目，保留老条目。
  val flushBranchRobIdx = IO(Input(UInt(CPUConfig.robPtrWidth.W)))

  // ---- 阶段 β：wakeup 端口 ----
  // 每个源携带 valid + pdst；下一拍把命中条目的 src{1,2}Ready 翻 1。
  // wakeup 源分三段：
  //   [0 .. refreshWidth)                               : Refresh 级（原有，兜底所有类型含 Load）
  //   [refreshWidth .. +memoryWidth)                    : Memory 级入口 ExMemDff.out（非 Load）
  //   [refreshWidth+memoryWidth .. +executeWidth)       : Execute 级入口 RRExDff.out（非 Load）
  // β 引入第三档，消费者 Issue 提前到 N+3、Exec 到 N+5；消费者 RR 在 N+4 与 Refresh 写
  // PRF 同拍，靠 PRF 写优先旁路命中新值。
  val NumWakeupSrcs: Int = CPUConfig.refreshWidth + CPUConfig.memoryWidth + CPUConfig.executeWidth
  val wakeup = IO(Input(Vec(NumWakeupSrcs, new IQWakeupSource)))

  // ---- 阶段 1：ReadyTable 查询端口 ----
  // IQ 入队当拍按本批各条目的 psrc1/psrc2 去查 ReadyTable，取其结果初始化 src{1,2}Ready。
  // 之所以把查询放在 IQ 入队而非 Rename，是为了避免 Rename→IQ 两级流水之间丢失 wakeup
  // 事件：若中途恰逢某 pdst 被 Refresh 置 ready，Rename 采样已过，而 IQ 还未建表，
  // wakeup 广播落空，将造成该 pdst 的消费者永远 ready=false 的死锁。
  val readyQuery = IO(new Bundle {
    val raddr = Output(Vec(8, UInt(CPUConfig.prfAddrWidth.W))) // 每拍 4 条指令 × 2 源
    val rdata = Input(Vec(8, Bool()))                          // 对应 ready 状态
  })

  // ========================================================================
  // 存储阵列：Vec[depth] 的平坦 IssueQueueEntry 数组
  // 空闲物理槽位列表：freeVec 位向量
  // ========================================================================
  val buffer    = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new IssueQueueEntry))))
  val validVec  = RegInit(VecInit(Seq.fill(depth)(false.B))) // 每个槽位是否有效
  val freeVec   = RegInit(VecInit(Seq.fill(depth)(true.B)))  // 初始状态：所有槽位空闲
  val freeCount = PopCount(freeVec)                          // 空闲槽位计数

  // 将空闲槽位数直接暴露给 Dispatch，由 Dispatch 根据 Rename 提供的 validCount 做精确比较。
  // 这样 IQ 内部就不再出现 canAlloc 依赖 request 的反向边，组合环路彻底消除。
  alloc.availCount := freeCount

  // Dispatch 已保证仅在真正派发成功时才把 alloc.request 抬起来（且 write.valid 同拍为高），
  // 这里只需要再用 flush 兜底，防止 Memory 重定向同拍误写。
  val doAlloc = write.valid && !flush

  // ---- FreeList 分配逻辑（级联 PriorityEncoder）----
  // 从 freeVec 中按优先级选择最多 4 个空闲槽位
  val freeIdxs = Wire(Vec(4, UInt(idxWidth.W)))
  val mask0 = freeVec.asUInt // 第 0 个：从 freeVec 中找第一个空闲槽
  freeIdxs(0) := PriorityEncoder(mask0)
  val mask1 = mask0 & ~(PriorityEncoderOH(mask0)) // 第 1 个：遮蔽前一个已选中的位，找下一个空闲槽
  freeIdxs(1) := PriorityEncoder(mask1)
  val mask2 = mask1 & ~(PriorityEncoderOH(mask1)) // 第 2 个：遮蔽前两个已选中的位，找下下一个空闲槽
  freeIdxs(2) := PriorityEncoder(mask2)
  val mask3 = mask2 & ~(PriorityEncoderOH(mask2)) // 第 3 个：遮蔽前三个已选中的位，找下下下一个空闲槽
  freeIdxs(3) := PriorityEncoder(mask3)

  // ---- 循环序号比较辅助函数（与 StoreBuffer.seqOlderThan 完全相同的思路）----
  // 由于 IQ 的活跃条目数始终 ≤ depth，而序号空间的半区间 2^(seqWidth-1) 被约束
  // 远大于 depth（见 CPUConfig.instSeqWidth 说明），因此“a - b 的最高位”即可安全判定先后。
  private def seqOlderThan(a: UInt, b: UInt): Bool = (a - b)(seqWidth - 1)

  // nextInstSeq：全局单调递增（但会自然回绕）的指令序号计数器。Dispatch 每实际分配 N 条，就向前推进 N。
  val nextInstSeq = RegInit(0.U(seqWidth.W))

  // ===================== 阶段 α.1（已暂时回滚）：Issue-fire 快速唤醒源 =====================
  // 尝试过 0-cycle 与 1-cycle 延迟的 Issue.fire 快速唤醒，都无法完全通过 example/test_build 回归，
  // 根因是 bypass 路径和唤醒时序不严格对齐，留待下一阶段专项解决，当前代码保持与原始 wakeup 一致。

  // ===================== 写入逻辑（Dispatch）=====================
  // 阶段 1：无论 doAlloc 是否成立，都把本批各条目的 psrc1/psrc2 送到 readyQuery.raddr 上，
  // 以便“同拍读 ReadyTable、同拍入队”并使用最新 ready 状态做初始化。
  for (i <- 0 until 4) {
    readyQuery.raddr(i * 2)     := write.entries(i).psrc1
    readyQuery.raddr(i * 2 + 1) := write.entries(i).psrc2
  }
  when(doAlloc) {
    for (i <- 0 until 4) {
      when(i.U < write.validCount) {
        val physIdx = freeIdxs(i)
        buffer(physIdx).data    := write.entries(i)
        buffer(physIdx).instSeq := nextInstSeq + i.U // 分配逻辑年龄
        // 阶段 1：用 IQ 入队当拍读到的 ReadyTable 值初始化 src{1,2}Ready。
        // ReadyTable 对 raddr=0 恒返回 true，因此 x0 源天然 ready。
        // 此外还要把本拍 wakeup 旁路叠加进来：ReadyTable 内部 table 寄存器的更新需下一拍才能
        // 被观察到，如果仅靠寄存器化后的 rdata，刚好命中“同拍 Refresh”的消费者就会错过那唯一
        // 一拍的 wakeup 广播，形成死锁。这里做一条组合旁路：wakeup 源 pdst 等于本 entry 的
        // psrcX 时，视为已就绪。
        val p1 = write.entries(i).psrc1
        val p2 = write.entries(i).psrc2
        val wk1ext = wakeup.map(w => w.valid && (w.pdst =/= 0.U) && (w.pdst === p1)).reduce(_ || _)
        val wk2ext = wakeup.map(w => w.valid && (w.pdst =/= 0.U) && (w.pdst === p2)).reduce(_ || _)
        // 阶段 α.1 已取消：去掉 fastWk Reg 路径。
        val wk1 = wk1ext
        val wk2 = wk2ext
        buffer(physIdx).src1Ready := readyQuery.rdata(i * 2)     || wk1
        buffer(physIdx).src2Ready := readyQuery.rdata(i * 2 + 1) || wk2
        validVec(physIdx) := true.B
        freeVec(physIdx)  := false.B
      }
    }
    nextInstSeq := nextInstSeq + write.validCount // 更新全局 instSeq 计数器（允许自然回绕）
  }

  // ===================== 阶段 α.1：wakeup 广播（下一拍生效）=====================
  // 对每个仍有效的条目，如果其 psrc1 或 psrc2 命中本拍任一 wakeup 源（包括 Refresh 级 wakeup
  // 以及本拍 Issue fire 的快速唤醒），则下一拍置 ready。
  // 注意：wakeup 与本拍 write 在不同物理槽位时互不干扰；若同拍写入的条目恰好命中
  // wakeup（极少见：被唤醒的 pdst == 该条目的 psrc），Dispatch 送进来的初始 ready
  // 已经反映了 ReadyTable 的最新值，wakeup 是锦上添花，不会错误地把 ready 置回 false。
  for (slot <- 0 until depth) {
    val e = buffer(slot)
    val hitExt1 = wakeup.map(w => w.valid && (w.pdst =/= 0.U) && (w.pdst === e.data.psrc1)).reduce(_ || _)
    val hitExt2 = wakeup.map(w => w.valid && (w.pdst =/= 0.U) && (w.pdst === e.data.psrc2)).reduce(_ || _)
    // 阶段 α.1 已取消：fast wakeup 在 example/test_build 上会造成晚期误匹配，暂时禁用。
    val hit1 = hitExt1
    val hit2 = hitExt2
    when(validVec(slot)) {
      when(hit1) { buffer(slot).src1Ready := true.B }
      when(hit2) { buffer(slot).src2Ready := true.B }
    }
  }

  // ===================== 发射选择逻辑（双发射：oldest-ready + 次老 ready）=====================
  // lane0：所有 ready 候选中 instSeq 最老（全功能，可发 Load/Store/Branch/ALU）。
  // lane1：剔除 lane0 后再 pick oldest-ready，且限定为"纯 ALU"（iType/rType/lui）。
  //   之所以限制 lane1 为 ALU：
  //   (a) Memory 流水单口，lane1 访存会与 lane0 冲突；
  //   (b) 分支/JALR 需要精确 flush，集中放在 lane0 简化恢复；
  //   (c) CSR/Trap 同理集中在 lane0。
  // 同拍 lane0→lane1 RAW 由 Issue 级禁 pair（此处不做）。
  private def lTypeOf(e: DispatchEntry): Bool = e.type_decode_together(4)
  private def sTypeOf(e: DispatchEntry): Bool = e.type_decode_together(2)
  // 判断条目是否可放在 lane1 发射（纯 ALU：iType/rType/lui）
  private def aluOnly(e: DispatchEntry): Bool = {
    val iType = e.type_decode_together(1)
    val rType = e.type_decode_together(0)
    val lui   = e.type_decode_together(8)
    iType || rType || lui
  }

  // lane0 memOrder：Load 与更老 Store 冲突（继承 1 发射语义）
  val olderStoreInIQ = Wire(Vec(depth, Bool()))
  for (i <- 0 until depth) {
    val hits = (0 until depth).map { j =>
      if (j == i) false.B
      else validVec(j) && sTypeOf(buffer(j).data) &&
        seqOlderThan(buffer(j).instSeq, buffer(i).instSeq)
    }
    olderStoreInIQ(i) := hits.reduce(_ || _)
  }

  // ---- lane0：oldest-ready ----
  val (issueIdx0, issueSeq0, issueFound0) = (0 until depth).foldLeft(
    (0.U(idxWidth.W), 0.U(seqWidth.W), false.B)
  ) { case ((bestIdx, bestSeq, found), i) =>
    val memOrderOK = !(lTypeOf(buffer(i).data) && olderStoreInIQ(i))
    val entryReady = validVec(i) && buffer(i).src1Ready && buffer(i).src2Ready && memOrderOK
    val better = entryReady && (!found || seqOlderThan(buffer(i).instSeq, bestSeq))
    (Mux(better, i.U(idxWidth.W), bestIdx),
     Mux(better, buffer(i).instSeq, bestSeq),
     found || entryReady)
  }

  // ---- lane1：次老 ready，且 aluOnly ----
  // 注意 memOrder：lane1 只可能是 ALU，不会是 Load/Store，因此不需要 memOrder 检查
  //
  // 【阶段 A1 配套】同拍 lane0 → lane1 投机唤醒：
  //   官方 wakeup 来自 Refresh（3~4 拍之后），直接等待 wakeup 会让"相邻两条 ALU 带 RAW"
  //   在 IQ 中被迫串行发射，Execute 侧的 EX-in-EX 前递通路（见 Execute.scala 阶段 A1）
  //   永远不会被触发，IPC 被压在 ~1。
  // 解决：在 IQ 选 lane1 时，若 lane1.psrcX === lane0.pdst，且 lane0 是非 Load
  //   （Load 同拍出不了结果，不能投机唤醒），就把该 src 视为本拍已经就绪。
  //   lane1 发射后，Execute 会用同拍前递把 lane0 的组合结果送到 lane1 的 ALU 输入端。
  val lane0Pdst      = buffer(issueIdx0).data.pdst
  val lane0RegWen    = buffer(issueIdx0).data.regWriteEnable
  val lane0IsLoadIQ  = lTypeOf(buffer(issueIdx0).data)
  // lane0 能否在本拍把结果前递给 lane1：必须真的发射、要写回、非 Load、pdst≠0
  val lane0SpecFwd   = issueFound0 && lane0RegWen && !lane0IsLoadIQ && (lane0Pdst =/= 0.U)
  val (issueIdx1, _, issueFound1, issue1UsesSpec) =
    if (CPUConfig.issueWidth >= 2) {
      (0 until depth).foldLeft(
        (0.U(idxWidth.W), 0.U(seqWidth.W), false.B, false.B)
      ) { case ((bestIdx, bestSeq, found, usesSpec), i) =>
        val notLane0 = !(issueFound0 && (issueIdx0 === i.U))
        // 同拍 lane0 → lane1 投机唤醒：若本条目的 psrcX 命中 lane0.pdst（且 lane0 可前递），
        // 则视为该 src 本拍已就绪。
        val spec1 = lane0SpecFwd && (buffer(i).data.psrc1 === lane0Pdst) && !buffer(i).src1Ready
        val spec2 = lane0SpecFwd && (buffer(i).data.psrc2 === lane0Pdst) && !buffer(i).src2Ready
        val src1ReadyEff = buffer(i).src1Ready || spec1
        val src2ReadyEff = buffer(i).src2Ready || spec2
        val entryReady = validVec(i) && src1ReadyEff && src2ReadyEff &&
          aluOnly(buffer(i).data) && notLane0
        val better = entryReady && (!found || seqOlderThan(buffer(i).instSeq, bestSeq))
        val entryUsesSpec = spec1 || spec2
        (Mux(better, i.U(idxWidth.W), bestIdx),
         Mux(better, buffer(i).instSeq, bestSeq),
         found || entryReady,
         Mux(better, entryUsesSpec, usesSpec))
      }
    } else (0.U(idxWidth.W), 0.U(seqWidth.W), false.B, false.B)

  // 默认发射口：全部不发射
  for (k <- 0 until CPUConfig.issueWidth) {
    issue(k).valid := false.B
    issue(k).bits  := 0.U.asTypeOf(new DispatchEntry)
  }
  issue(0).valid := issueFound0
  issue(0).bits  := buffer(issueIdx0).data
  if (CPUConfig.issueWidth >= 2) {
    // 若 lane1 的就绪依赖于 lane0 的同拍投机唤醒，则必须等 lane0 也 fire 才允许 lane1 fire，
    // 否则 lane1 拿到的是"没人喂的伪 ready"，会读到 PRF 中过时的 src 数据，语义错乱。
    issue(1).valid := issueFound1 && (!issue1UsesSpec || issue(0).fire)
    issue(1).bits  := buffer(issueIdx1).data
  }

  // ---- 阶段 α.1（已回滚）：fast wakeup 导出逻辑已移除，此处不再产生任何 wake 源。----

  // ---- 释放已发射槽位 ----
  // lane0/lane1 各自独立。若同拍两个 lane 选中同一 slot（理论上已被 notLane0 剔除），
  // 下面 when 链按顺序执行，最终结果仍正确。
  when(issue(0).fire) {
    validVec(issueIdx0) := false.B
    freeVec(issueIdx0)  := true.B
  }
  if (CPUConfig.issueWidth >= 2) {
    when(issue(1).fire) {
      validVec(issueIdx1) := false.B
      freeVec(issueIdx1)  := true.B
    }
  }

  // ===================== 回滚逻辑（Memory 重定向，按 robIdx 精确选择）=====================
  // OoO 下 IQ 中可能同时存在“比误预测分支更老”但因依赖未就绪而未发射的指令，
  // 直接全清会破坏精确异常/提交语义。改为仅清除 robIdx 比分支更年轻的条目。
  // nextInstSeq 不再复位：老条目仍持有其 instSeq，需要继续比较使用。
  private def robYoungerThan(a: UInt, b: UInt): Bool = {
    val w = CPUConfig.robPtrWidth
    ((a - b)(w - 1) === 0.U) && (a =/= b)
  }
  when(flush) {
    for (i <- 0 until depth) {
      when(validVec(i) && robYoungerThan(buffer(i).data.robIdx, flushBranchRobIdx)) {
        validVec(i) := false.B
        freeVec(i)  := true.B
      }
    }
  }
}
