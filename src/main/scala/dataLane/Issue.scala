package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// Issue 阶段 Load-Use 冒险检测源：标识某个下游流水级中尚未把数据写回 PRF 的 Load
class LoadHazardSource extends Bundle {
  val pdst        = UInt(CPUConfig.prfAddrWidth.W) // 该级内 Load 的物理目的寄存器编号
  val isValidLoad = Bool()                          // 该级 payload 有效且为 Load
}

// ============================================================================
// Issue（发射阶段）—— 双发射版本：lane0 全功能 + lane1 纯 ALU
// ============================================================================
// IssueQueue 已实现 oldest-ready + 次老 ready 的双选择器，Issue 仅做 per-lane
// 合法性检查与冒险检测：
//
//   lane0：可接收 Load/Store/Branch/JALR/iType/rType/lui/auipc 等全功能指令
//   lane1：仅当 IQ 送来的是纯 ALU 指令（iType/rType/lui）时有效；否则 lane1 无效
//          Branch/JALR/Load/Store/auipc 等集中在 lane0，简化 flush 与访存串行化
//
// Load-Use 冒险：
//   - 下游 3 级 Load（ReadReg/Execute/Memory）的 pdst 仅可能来自 lane0（因为
//     lane1 永远不产 Load），所以 hazard 源向量保持 3 项标量，无需扩 Vec
//   - lane0/lane1 各自独立检测其 psrc1/psrc2 是否命中任一下游 Load
//
// 同拍 lane0→lane1 RAW：
//   - 若 lane1 的 psrc1/psrc2 = lane0 的 pdst（且 lane0 规定 regWriteEnable），
//     Execute 阶段的 EX→EX 旁路要从"下一拍"才能覆盖到 lane1，而 lane0/lane1
//     本拍同在 ReadReg 阶段，无旁路可取；禁 pair 最简单安全：lane1.validMask=0
//
// 选择性 flush：
//   - 对每 lane 独立按 robIdx 与 flushBranchRobIdx 比较，只丢弃年轻条目
//
// ============================================================================
class Issue extends Module {
  val out = IO(Decoupled(new Issue_ReadReg_Payload))
  val flush = IO(Input(Bool()))
  val flushBranchRobIdx = IO(Input(UInt(CPUConfig.robPtrWidth.W)))

  // ---- IssueQueue 发射选择接口（Vec 化）----
  val iqIssue = IO(Vec(CPUConfig.issueWidth, Flipped(Decoupled(new DispatchEntry))))

  // ---- Load-Use 冒险检测接口 ----
  //   TD-B：loadLanes 升到 2 条，每个 Load lane 都可能在下游 RR/Execute/Memory 持有未写
  //   回 PRF 的 Load，因此 hazard 槽位扩到 3 * loadLanes.size = 6；最后再加上 MSHR
  //   Vec(2) 两槽，共 8 项。MyCPU 顶层负责按 lane 顺序填充每个槽位的 pdst/isValidLoad。
  val NumLoadHazardSrcs: Int = 3 * LaneCapability.loadLanes.size + 2
  val hazard = IO(Input(Vec(NumLoadHazardSrcs, new LoadHazardSource)))

  private val robW = CPUConfig.robPtrWidth

  // ---- 辅助：对单条指令进行解码 & 冒险检测 ----
  // Phase A.2：以下 useRs1 / useRs2 / aluOnly 改为 LaneCapability helper 单点定义。
  private def useRs1(td: UInt): Bool = LaneCapability.useRs1(td)
  private def useRs2(td: UInt): Bool = LaneCapability.useRs2(td)
  // 判断某条指令是否可放在 ALU-only lane 发射（纯 ALU：iType/rType/lui）
  private def aluOnly(td: UInt): Bool = LaneCapability.isAluOnly(td)

  // ---- 预先算每 lane 的 loadUse / youngerThanBranch / aluOnly ----
  val laneValid        = Wire(Vec(CPUConfig.issueWidth, Bool()))
  val laneCanIssue     = Wire(Vec(CPUConfig.issueWidth, Bool()))
  val laneEntries      = Wire(Vec(CPUConfig.issueWidth, new DispatchEntry))

  for (k <- 0 until CPUConfig.issueWidth) {
    val entry = iqIssue(k).bits
    val entryValid = iqIssue(k).valid
    val td = entry.type_decode_together
    val u1 = useRs1(td); val u2 = useRs2(td)
    val loadUseStall = hazard.map { h =>
      h.isValidLoad && (h.pdst =/= 0.U) &&
        ((u1 && (entry.psrc1 === h.pdst)) ||
         (u2 && (entry.psrc2 === h.pdst)))
    }.reduce(_ || _)
    val entryYoungerThanBranch =
      ((entry.robIdx - flushBranchRobIdx)(robW - 1) === 0.U) && (entry.robIdx =/= flushBranchRobIdx)
    val laneAluOnly = LaneCapability.canIssueOn(k, td)
    laneEntries(k) := entry
    laneValid(k)   := entryValid
    laneCanIssue(k) := entryValid && !loadUseStall &&
      (!flush || !entryYoungerThanBranch) && laneAluOnly
  }

  // ---- lane1 RAW 禁 pair：仅在 lane0 是 Load（lType）时才需要禁 pair ----
  // 背景（阶段 A1）：
  //   - 非 Load 指令（ALU / LUI / AUIPC / JAL / JALR）在 Execute 同拍即可产出结果，
  //     我们已在 Execute.scala 增加 lane0 → lane1 的同拍前递通路，可以无缝解决
  //     "同拍 lane0 写 pdst、lane1 读同一 pdst" 的 RAW 依赖。
  //   - Load 指令的结果要等到下一拍 Memory 阶段才能写回，Execute 内的同拍前递
  //     无法获取其数据，此时仍必须禁 pair，lane1 在下一拍靠 hazard 机制继续等待。
  // ---- 同拍 pair RAW（O(N²)）：lane(j<k) 的 pdst → lane(k) 的 src ----
  // Phase A.6：把原"仅 lane0 Load → lane1"的硬编码 RAW 检测扩到任意 N 发射。
  // 决策规则：
  //   - 仅当 lane j 是 Load 时禁 pair（Load 同拍出不了数据，Execute 内同拍前递
  //     无法供给 lane k；非 Load 都可以靠 Execute 的 lane(j<k)→lane(k) 同拍前
  //     递通路覆盖，详见 Phase C 的 Execute 改造）。
  //   - lane j 必须实际可发射（laneCanIssue(j)）、写回非 0、pdst 与 lane k
  //     的 psrc1/psrc2 命中。
  // pairRaw(k) = OR over j < k 是否与某个更老 lane 形成 Load-RAW 冲突。
  val pairRaw = Wire(Vec(CPUConfig.issueWidth, Bool()))
  pairRaw(0) := false.B
  for (k <- 1 until CPUConfig.issueWidth) {
    val tdK = laneEntries(k).type_decode_together
    val u1k = useRs1(tdK); val u2k = useRs2(tdK)
    val conflicts = (0 until k).map { j =>
      val laneJWritesPdst = laneCanIssue(j) && laneEntries(j).regWriteEnable &&
        (laneEntries(j).pdst =/= 0.U)
      val laneJIsLoad = LaneCapability.isLType(laneEntries(j).type_decode_together)
      laneJWritesPdst && laneJIsLoad && (
        (u1k && (laneEntries(k).psrc1 === laneEntries(j).pdst)) ||
        (u2k && (laneEntries(k).psrc2 === laneEntries(j).pdst))
      )
    }
    pairRaw(k) := conflicts.reduce(_ || _)
  }

  // TD-B/TD-C：禁止"两条 Load / 两条 Branch 同拍发射"——
  //   - Memory.scala 仍是 single-Load-per-cycle（单 MSHR-grant + 单 AR）；
  //   - Memory.redirect 仍是单端口，TD-C MVP 用 single-Branch-per-cycle 仲裁。
  // 检查"任意更老 lane j<k 是 Load/Branch"，若是则 lane k 不许再发同类型。
  val finalIssue = Wire(Vec(CPUConfig.issueWidth, Bool()))
  finalIssue(0) := laneCanIssue(0)
  for (k <- 1 until CPUConfig.issueWidth) {
    val tdK = laneEntries(k).type_decode_together
    val kIsBr   = LaneCapability.isBType(tdK) || LaneCapability.isJalr(tdK)
    val olderBrActive = (0 until k).map { j =>
      val tdJ = laneEntries(j).type_decode_together
      laneCanIssue(j) && (LaneCapability.isBType(tdJ) || LaneCapability.isJalr(tdJ))
    }.foldLeft(false.B)(_ || _)
    // TD-E-4（v19 解除 doubleLoadStall）：
    //   - Memory.scala 已落地双 capture / per-lane mshrCaptureFire / AR 仲裁
    //     （pending > fresh-oldest）/ all-or-none accept rule；
    //   - mshrComplete IO=Vec(2)，per-slot 独立 ack（TD-D 沿用）；
    //   - βwake 用严格门控 `RRExDff.fire && Memory.in.ready`（v19 TD-E）保证
    //     dual-Load 反压频率上升时也不破坏 N+3 拍 PRF 写时序假设。
    //   故本拍 lane0/lane1 同时是 Load 时不再 stall lane1。
    // 仍保留：doubleBrStall（Memory.redirect 单端口）+ pairRaw（lane0 写、lane1 读）。
    val doubleBrStall   = kIsBr && olderBrActive
    finalIssue(k) := laneCanIssue(k) && !pairRaw(k) && !doubleBrStall
  }

  // ---- 输出 payload：按 lane 写入 ----
  out.bits := DontCare
  val validMaskBits = Wire(Vec(CPUConfig.issueWidth, Bool()))
  for (k <- 0 until CPUConfig.issueWidth) {
    val e  = laneEntries(k)
    val ln = out.bits.lanes(k)
    ln.pc                   := e.pc
    ln.inst                 := e.inst
    ln.imm                  := e.imm
    ln.type_decode_together := e.type_decode_together
    ln.predict_taken        := e.predict_taken
    ln.predict_target       := e.predict_target
    ln.bht_meta             := e.bht_meta
    ln.robIdx               := e.robIdx
    ln.regWriteEnable       := e.regWriteEnable
    ln.sbIdx                := e.sbIdx
    ln.isSbAlloc            := e.isSbAlloc
    ln.storeSeqSnap         := e.storeSeqSnap
    ln.psrc1                := e.psrc1
    ln.psrc2                := e.psrc2
    ln.pdst                 := e.pdst
    ln.checkpointIdx        := e.checkpointIdx
    validMaskBits(k) := finalIssue(k)
  }
  out.bits.validMask := validMaskBits.asUInt

  // out.valid：只要任一 lane 有效就向下游推进
  out.valid := finalIssue.reduce(_ || _)

  // ---- 通知 IQ 各 lane 是否被消费 ----
  // 下游 out.ready 对本拍所有 lane 一起生效
  for (k <- 0 until CPUConfig.issueWidth) {
    iqIssue(k).ready := finalIssue(k) && out.ready
  }
}
