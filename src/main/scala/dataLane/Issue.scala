package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

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
// 无寄存器重命名版本：使用逻辑寄存器编号 rd(5-bit) 进行 Load-Use 冒险检测
// 输出 Issue_ReadReg_Payload 给 IssRRDff → ReadReg 阶段
// ============================================================================
class Issue extends Module {
  val out = IO(Decoupled(new Issue_ReadReg_Payload))          // 输出：送往 IssRRDff → ReadReg
  val flush = IO(Input(Bool()))                               // 流水线冲刷信号

  // ---- IssueQueue 发射选择接口 ----
  val iqIssue = IO(Flipped(new IQIssueIO))

  // ---- Load-Use 冒险检测接口 ----
  // 从 IssRRDff（Issue → ReadReg 流水寄存器）获取正在进入 ReadReg 的指令信息
  // 无寄存器重命名版本：使用逻辑目标寄存器 rd(5-bit) 进行匹配
  val hazard = IO(new Bundle {
    val rd          = Input(UInt(5.W))  // ReadReg 阶段指令的逻辑目标寄存器编号
    val isValidLoad = Input(Bool())     // 该指令是否为有效 Load
  })

  // ---- 从 IssueQueue 获取最老的有效指令 ----
  val entry = iqIssue.entry
  val entryValid = iqIssue.valid

  // ---- 指令字段提取 ----
  val inst = entry.inst
  val rs1  = inst(19, 15) // 逻辑源寄存器 1
  val rs2  = inst(24, 20) // 逻辑源寄存器 2

  // ---- 指令类型提取（从 9 位独热编码）----
  val td    = entry.type_decode_together
  val jalr  = td(6)
  val bType = td(5)
  val lType = td(4)
  val iType = td(3)
  val sType = td(2)
  val rType = td(1)

  // ---- Load-Use 冒险检测 ----
  // 无寄存器重命名版本：使用逻辑寄存器编号进行匹配
  // rd=x0 不会产生冒险（x0 硬编码为 0）
  val use_rs1 = jalr || bType || iType || sType || rType || lType
  val use_rs2 = bType || sType || rType
  val loadUseStall = hazard.isValidLoad && (hazard.rd =/= 0.U) &&
    ((use_rs1 && (rs1 === hazard.rd)) ||
     (use_rs2 && (rs2 === hazard.rd)))

  // ---- 是否可以发射当前指令 ----
  val canIssue = entryValid && !loadUseStall && !flush

  // ---- 输出结果打包（最老指令信息）----
  out.bits.pc                   := entry.pc
  out.bits.inst                 := entry.inst
  out.bits.imm                  := entry.imm
  out.bits.type_decode_together := td
  out.bits.predict_taken        := entry.predict_taken
  out.bits.predict_target       := entry.predict_target
  out.bits.bht_meta             := entry.bht_meta
  out.bits.robIdx               := entry.robIdx
  out.bits.regWriteEnable       := entry.regWriteEnable
  out.bits.sbIdx                := entry.sbIdx
  out.bits.isSbAlloc            := entry.isSbAlloc
  out.bits.storeSeqSnap         := entry.storeSeqSnap

  // ---- 握手信号 ----
  out.valid := canIssue

  // 通知 IssueQueue 确认发射，释放该槽位
  iqIssue.fire := canIssue && out.ready
}
