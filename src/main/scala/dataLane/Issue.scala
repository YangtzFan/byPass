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

  // ---- Load-Use 冒险检测接口：仍然 3 项标量（lane1 永不产 Load）----
  val NumLoadHazardSrcs: Int = 3
  val hazard = IO(Input(Vec(NumLoadHazardSrcs, new LoadHazardSource)))

  private val robW = CPUConfig.robPtrWidth

  // ---- 辅助：对单条指令进行解码 & 冒险检测 ----
  private def useRs1(td: UInt): Bool = {
    val jalr  = td(6); val bType = td(5); val lType = td(4)
    val iType = td(3); val sType = td(2); val rType = td(1)
    jalr || bType || iType || sType || rType || lType
  }
  private def useRs2(td: UInt): Bool = {
    val bType = td(5); val sType = td(2); val rType = td(1)
    bType || sType || rType
  }
  // 判断某条指令是否可放在 lane1 发射（纯 ALU：iType/rType/lui）
  private def aluOnly(td: UInt): Bool = {
    val iType = td(3); val rType = td(1); val lui = td(8)
    iType || rType || lui
  }

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
    val laneAluOnly = if (k == 0) true.B else aluOnly(td)
    laneEntries(k) := entry
    laneValid(k)   := entryValid
    laneCanIssue(k) := entryValid && !loadUseStall &&
      (!flush || !entryYoungerThanBranch) && laneAluOnly
  }

  // ---- lane1 RAW 禁 pair：若 lane0 会发射且会写 pdst，且 lane1 的 psrc 命中 lane0.pdst ----
  val lane0WritesPdst = if (CPUConfig.issueWidth >= 2) {
    laneCanIssue(0) && laneEntries(0).regWriteEnable && (laneEntries(0).pdst =/= 0.U)
  } else false.B
  val pairRawConflict = if (CPUConfig.issueWidth >= 2) {
    val td1 = laneEntries(1).type_decode_together
    val u1_1 = useRs1(td1); val u2_1 = useRs2(td1)
    lane0WritesPdst && (
      (u1_1 && (laneEntries(1).psrc1 === laneEntries(0).pdst)) ||
      (u2_1 && (laneEntries(1).psrc2 === laneEntries(0).pdst))
    )
  } else false.B

  val finalIssue = Wire(Vec(CPUConfig.issueWidth, Bool()))
  finalIssue(0) := laneCanIssue(0)
  if (CPUConfig.issueWidth >= 2) {
    finalIssue(1) := laneCanIssue(1) && !pairRawConflict
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
