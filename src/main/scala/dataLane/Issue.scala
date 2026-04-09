package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// Issue（发射阶段）—— 当前版本：顺序单发射（宽度 1）
// ============================================================================
// 从 IssueQueue 接收最多 4 条指令（4-wide 出队端口），当前版本只选择第 0 路
// （队首指令）进行发射。通过 iqDeqCount 通知 IssueQueue 实际消费了多少条。
//
// 发射阻止条件：
//   - Load-Use 冒险：当下游正在执行的指令是 Load，且当前指令的源寄存器
//     依赖该 Load 的目标寄存器时，暂停发射一个周期
//
// 后续多发射版本只需扩展此模块，选择多路指令并增大 iqDeqCount 即可。
//
// 输出 Issue_ReadReg_Payload 给 IssRRDff → ReadReg 阶段
// ============================================================================
class Issue extends Module {
  val out = IO(Decoupled(new Issue_ReadReg_Payload))          // 输出：送往 IssRRDff → ReadReg
  val flush = IO(Input(Bool()))                               // 流水线冲刷信号

  // ---- IssueQueue 4-wide 出队接口 ----
  val in = IO(Input(Vec(4, new DispatchEntry)))
  val fetchCount = IO(Output(UInt(3.W)))

  // ---- Load-Use 冒险检测接口 ----
  // 从 RRExDff（ReadReg → Execute 流水线寄存器）获取正在进入 Execute 的指令信息
  val hazard = IO(new Bundle {
    val rd          = Input(UInt(5.W)) // Execute 阶段指令的目标寄存器编号
    val isValidLoad = Input(Bool())    // 该指令是否为 Load
  })

  // ---- 当前版本只看第 0 路（队首指令）----
  val entry = in(0)
  val entryValid = in(0).valid

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
  // 检测当前指令的源寄存器是否依赖正在执行的 Load 指令的目标寄存器
  // 如果依赖，Load 数据要到 Memory 阶段才能通过旁路转发可用，因此需要暂停 1 拍
  val use_rs1 = jalr || bType || iType || sType || rType || lType
  val use_rs2 = bType || sType || rType
  val loadUseStall = hazard.isValidLoad && (hazard.rd =/= 0.U) &&
    ((use_rs1 && (rs1 === hazard.rd)) ||
     (use_rs2 && (rs2 === hazard.rd)))

  // ---- 是否可以发射当前指令 ----
  val canIssue = entryValid && !loadUseStall && !flush

  // ---- 输出结果打包（第 0 路指令信息）----
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

  // ---- 握手信号 ----
  out.valid := canIssue

  // 通知 IssueQueue 实际出队数量：当前版本只出队 0 或 1 条，出队 0 条相当于 ready 反压
  fetchCount := Mux(canIssue && out.ready, 1.U, 0.U)
}
