package mycpu.dataLane

import chisel3._
import mycpu.CPUConfig

// ============================================================================
// LaneCapability —— 单点定义"指令类型 / lane 能力 / lane 路由"的辅助 helper
// ============================================================================
// 背景：随着 issueWidth 由 1 → 2 → 4 演进，IQ / Issue / Execute / Memory 等多个
// 模块都需要重复判断"某条指令能否放在第 k 条 lane"。如果每个模块各自硬编码
// `td(3)`/`td(1)`/`td(8)` 等位下标，极易出现位宽错位的 BUG（参见 TD-001/004：
// IssueQueue 的 aluOnly 历史 BUG）。本 helper 把所有判定收口到此处。
//
// 编码源：Decode.scala 中
//   type_decode_together = Cat(uType, jal, jalr, bType, lType, iType, sType, rType, other)
// 即（LSB → MSB）：
//   bit0=other, bit1=rType, bit2=sType, bit3=iType, bit4=lType,
//   bit5=bType, bit6=jalr,  bit7=jal,   bit8=uType
//
// LaneCapability 含两类 API：
//   (a) 单条指令分类谓词（is*）—— 输入 type_decode_together，输出 Bool；
//   (b) lane 能力查询（canIssueOn / fullLanes / aluLanes）—— 输入 lane 索引（编译期 Int），
//       输出该 lane 是否可发射对应类型。
//
// 当前 lane 分配（issueWidth = 2/4 都适用，D' 之前）：
//   - lane0：Full（ALU + 分支验证 + JALR + Load/Store + CSR/FENCE）
//   - lane1+：ALU-only（iType / rType / uType）
// 阶段 D' 落地双 Mem 口后，会扩展 lane1 为 Full（修改 fullLaneSet 即可）。
// ============================================================================
object LaneCapability {

  // -----------------------------------------------------------------------
  // (a) 指令类型分类谓词（直接对接 type_decode_together 9-bit 编码）
  // -----------------------------------------------------------------------
  def isOther(td: UInt): Bool = td(0)            // FENCE / ECALL / 未知
  def isRType(td: UInt): Bool = td(1)            // R-type ALU（add/sub/...）
  def isSType(td: UInt): Bool = td(2)            // Store
  def isIType(td: UInt): Bool = td(3)            // I-type ALU（addi/slli/...）
  def isLType(td: UInt): Bool = td(4)            // Load
  def isBType(td: UInt): Bool = td(5)            // 条件分支
  def isJalr (td: UInt): Bool = td(6)
  def isJal  (td: UInt): Bool = td(7)
  def isUType(td: UInt): Bool = td(8)            // LUI / AUIPC

  /** 是否使用 rs1（用于 hazard / bypass 触发）。 */
  def useRs1(td: UInt): Bool =
    isJalr(td) || isBType(td) || isLType(td) ||
    isIType(td) || isSType(td) || isRType(td)

  /** 是否使用 rs2。 */
  def useRs2(td: UInt): Bool =
    isBType(td) || isSType(td) || isRType(td)

  /** 该指令是否在 Execute 阶段同拍即可产出最终结果（适用 EX-in-EX 前递）。
    * 注意：Load 必须等 Memory 才有数据，sType / bType / other 不写回。
    */
  def producesEarlyResult(td: UInt): Bool =
    isUType(td) || isJal(td) || isJalr(td) || isIType(td) || isRType(td)

  /** 该指令仅占 ALU lane（无访存 / 无分支 / 无 JALR / 非 FENCE）。 */
  def isAluOnly(td: UInt): Bool =
    isIType(td) || isRType(td) || isUType(td)

  /** 阶段 D'（双 Mem 口）：能放在"ALU + Store"型 lane 的指令集合。
    * Store 走 SB，仅做地址 + 字节对齐写入 StoreBuffer，不需要 Load forwarding /
    * MSHR / AXI 仲裁，因此可以放在不具备完整 Mem 能力的次级 lane 上。 */
  def isAluOrStore(td: UInt): Bool =
    isAluOnly(td) || isSType(td)

  /** 该指令是否需要走 Memory 流水（Load 或 Store）。 */
  def isMemOp(td: UInt): Bool =
    isLType(td) || isSType(td)

  /** 该指令是否会改变控制流（除顺序 PC+4 外）。 */
  def isCtrlFlow(td: UInt): Bool =
    isBType(td) || isJal(td) || isJalr(td)

  // -----------------------------------------------------------------------
  // (b) lane 能力配置（编译期常量；改 lane 分配只动这里）
  // -----------------------------------------------------------------------
  // fullLaneSet：能跑全部指令类型（Mem / 分支 / JALR / CSR / ALU）的 lane 索引集合。
  // TD-B（lane1+Load）：lane0/1 同为 Load+Store+ALU lane。Branch/JALR 仍仅 lane0
  // 处理（见 branchLaneSet），TD-C 阶段再放开。
  val fullLaneSet: Set[Int] = Set(0, 1)

  // storeLaneSet：能额外承接 Store 的 lane 索引集合（Full lane 自动属于此集合）。
  val storeLaneSet: Set[Int] = Set(0, 1)

  // branchLaneSet：能解析 Branch/JALR 的 lane 集合。
  // TD-C：放开到 {0,1}，配合 Issue.scala 的 "no-double-Branch" 门控，让 lane1 在 lane0
  // 是 Mem/ALU 时也能承接 Branch/JALR，减少 Branch 在 lane0 的串行排队。
  val branchLaneSet: Set[Int] = Set(0, 1)

  /** 编译期：lane k 是否为 Full（可发任何指令）。 */
  def isFullLane(k: Int): Boolean = fullLaneSet.contains(k)

  /** 编译期：lane k 是否仅能发 ALU。 */
  def isAluOnlyLane(k: Int): Boolean = !isFullLane(k) && !isStoreLane(k)

  /** 编译期：lane k 是否能跑 Store（含 Full lane）。 */
  def isStoreLane(k: Int): Boolean = storeLaneSet.contains(k)

  /** 编译期：lane k 是否能跑 Load（含 Full lane）。当前 lane0/1 都支持 Load。 */
  def isLoadLane(k: Int): Boolean = isFullLane(k)

  /** 编译期：lane k 是否能跑 Memory 操作（Load 或 Store 任一）。 */
  def isMemLane(k: Int): Boolean = isFullLane(k) || isStoreLane(k)

  /** 编译期：lane k 是否能解析分支/JALR。
    * TD-B 阶段仅 lane0；TD-C 完成后此函数会改回 isFullLane(k)。 */
  def isBranchLane(k: Int): Boolean = branchLaneSet.contains(k)

  /** 给定 lane 索引（编译期 Int）和指令类型编码 td，返回是否允许在该 lane 发射。
    * 实际的 RAW pair 禁发由 Issue.scala 的 pairRawConflict 单独处理，本函数只负责
    * "该 lane 是否具备执行该类型指令的能力"。
    *
    * TD-B 关键：lane1 是 Full 但**不**接受 Branch/JALR/Other（FENCE 等）。
    */
  def canIssueOn(k: Int, td: UInt): Bool = {
    if (isFullLane(k) && isBranchLane(k)) {
      // 真·全功能 lane：任何指令类型
      true.B
    } else if (isFullLane(k)) {
      // TD-B：lane1 = Full 但不含 Branch/JALR/Other → 允许 ALU + Store + Load
      isAluOnly(td) || isSType(td) || isLType(td)
    } else if (isStoreLane(k)) {
      isAluOrStore(td)
    } else {
      isAluOnly(td)
    }
  }

  /** 所有 Full lane 的索引列表（升序）。 */
  def fullLanes: Seq[Int] = (0 until CPUConfig.issueWidth).filter(isFullLane)

  /** 所有 Load 能力 lane 的索引列表（升序，含 Full lane）。 */
  def loadLanes: Seq[Int] = (0 until CPUConfig.issueWidth).filter(isLoadLane)

  /** 所有可承接 Store 的 lane 索引列表（升序，含 Full lane）。 */
  def storeLanes: Seq[Int] = (0 until CPUConfig.issueWidth).filter(isStoreLane)

  /** 所有 ALU-only lane 的索引列表。 */
  def aluLanes: Seq[Int] = (0 until CPUConfig.issueWidth).filter(isAluOnlyLane)
}
