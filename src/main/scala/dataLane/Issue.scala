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
// Issue（发射阶段）—— 当前版本：顺序单发射（宽度 1）
// ============================================================================
// 从 IssueQueue 的发射选择接口接收最老的有效指令，进行 Load-Use 冒险检测后发射。
// IssueQueue 已改为 Vec + FreeList + instSeq 架构，通过 issue 接口交互：
//   - IssueQueue 输出最老的有效条目（issue.valid, issue.entry）
//   - Issue 确认发射后通过 issue.fire 通知 IssueQueue 释放槽位
//
// 发射阻止条件：
//   - Load-Use 冒险：当下游正在执行的指令是 Load，且当前指令的源寄存器
//     依赖该 Load 的目标寄存器时，暂停发射一个周期
//
// 输出 Issue_ReadReg_Payload 给 IssRRDff → ReadReg 阶段
// ============================================================================
class Issue extends Module {
  val out = IO(Decoupled(new Issue_ReadReg_Payload))          // 输出：送往 IssRRDff → ReadReg
  val flush = IO(Input(Bool()))                               // 流水线冲刷信号
  // OoO 场景下，flush 携带误预测分支的 robIdx，仅比它更年轻的条目才真正被丢弃。
  val flushBranchRobIdx = IO(Input(UInt(CPUConfig.robPtrWidth.W)))

  // ---- IssueQueue 发射选择接口 ----
  val iqIssue = IO(Flipped(Decoupled(new DispatchEntry)))

  // ---- Load-Use 冒险检测接口 ----
  // 为避免 Post-Refresh 这一级旁路带来的复杂度，将全部 Load-Use 冒险检测上提到 Issue 阶段：
  // 如果下游有任意一级仍在流动的 Load（尚未把数据写回 PRF），且其物理目的寄存器匹配
  // 当前待发射指令的任一物理源寄存器，则本拍不发射，让该指令继续留在 IssueQueue 中。
  //
  // 需要覆盖的下游 Load 位置（以 pdst 唯一标识）：
  //   (a) IssRRDff → ReadReg 阶段：最紧邻的下一条指令
  //   (b) RRExDff  → Execute  阶段：下下一条指令
  //   (c) ExMemDff → Memory   阶段：下下下一条指令（Load 数据此拍才从 DRAM 返回）
  // 当 Load 到达 MemRefDff（Refresh 阶段）时：Refresh 本拍写 PRF，ReadReg 对 PRF 的读取
  // 发生在 Issue 之后的下一拍，彼时 PRF 已是新值，不需要继续在 Issue 停顿。
  // 因此只需覆盖以上 3 级即可消除所有 Load-Use 风险。
  val NumLoadHazardSrcs: Int = 3
  val hazard = IO(Input(Vec(NumLoadHazardSrcs, new LoadHazardSource)))

  // ---- 从 IssueQueue 获取最老的有效指令 ----
  val entry = iqIssue.bits
  val entryValid = iqIssue.valid

  // ---- 指令字段提取 ----
  val inst = entry.inst
  val rs1  = inst(19, 15)
  val rs2  = inst(24, 20)

  // ---- 指令类型提取（从 9 位独热编码）----
  val td    = entry.type_decode_together
  val jalr  = td(6)
  val bType = td(5)
  val lType = td(4)
  val iType = td(3)
  val sType = td(2)
  val rType = td(1)

  // ---- Load-Use 冒险检测 ----
  // 使用物理寄存器编号进行匹配，避免逻辑寄存器重名问题。
  // 只要下游任意一级存在尚未把结果写进 PRF 的 Load，且其 pdst 与当前指令真正使用到的
  // psrc 匹配，就停顿本拍发射。
  val use_rs1 = jalr || bType || iType || sType || rType || lType
  val use_rs2 = bType || sType || rType
  val loadUseStall = hazard.map { h =>
    h.isValidLoad && (h.pdst =/= 0.U) &&
      ((use_rs1 && (entry.psrc1 === h.pdst)) ||
       (use_rs2 && (entry.psrc2 === h.pdst)))
  }.reduce(_ || _)

  // ---- 是否可以发射当前指令 ----
  // OoO 选择性 flush：即便处于 flush 拍，若当前待发射条目的 robIdx ≤ 分支 robIdx（即不比分支年轻），
  // 仍允许其发射，使老指令继续推进以完成 ROB 提交，避免头阻塞死锁。
  private val robW = CPUConfig.robPtrWidth
  val entryYoungerThanBranch =
    ((entry.robIdx - flushBranchRobIdx)(robW - 1) === 0.U) && (entry.robIdx =/= flushBranchRobIdx)
  val canIssue = entryValid && !loadUseStall && (!flush || !entryYoungerThanBranch)

  // ---- 输出结果打包（最老指令信息）----
  // 阶段 2 起 payload 改为 Vec(issueWidth, Lane)+validMask 形式；宽度 1 时
  // 仅写 lanes(0)，其余 lane 置默认值（DontCare）。
  out.bits := DontCare
  val outLane0 = out.bits.lanes(0)
  outLane0.pc                   := entry.pc
  outLane0.inst                 := entry.inst
  outLane0.imm                  := entry.imm
  outLane0.type_decode_together := td
  outLane0.predict_taken        := entry.predict_taken
  outLane0.predict_target       := entry.predict_target
  outLane0.bht_meta             := entry.bht_meta
  outLane0.robIdx               := entry.robIdx
  outLane0.regWriteEnable       := entry.regWriteEnable
  outLane0.sbIdx                := entry.sbIdx
  outLane0.isSbAlloc            := entry.isSbAlloc
  outLane0.storeSeqSnap         := entry.storeSeqSnap
  // 物理寄存器映射信息透传
  outLane0.psrc1                := entry.psrc1
  outLane0.psrc2                := entry.psrc2
  outLane0.pdst                 := entry.pdst
  outLane0.checkpointIdx        := entry.checkpointIdx // 分支 checkpoint 索引透传
  // validMask：Issue 按 lane 标记实际有效位；宽度 1 时即 1.U
  out.bits.validMask := canIssue.asUInt

  // ---- 握手信号 ----
  out.valid := canIssue

  // 通知 IssueQueue 确认发射，释放该槽位
  iqIssue.ready := canIssue && out.ready
}
