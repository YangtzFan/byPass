package mycpu.dataLane

import chisel3._
import chisel3.util._

// ============================================================================
// BaseDff —— 通用流水线寄存器（使用 Decoupled 握手协议）
// ============================================================================
// 功能：在相邻流水级之间缓存数据，使用 valid/ready 反压握手：
//   - in.valid  : 上游数据有效
//   - in.ready  : 本级可以接收（当前无有效数据 或 下游已取走）
//   - out.valid : 本级有有效数据等待下游取走
//   - out.ready : 下游可以接收
//
// 参数：
//   gen          : 数据类型（如 FetchBufferEntry、DecodeOut 等 Bundle）
//   supportFlush : 是否需要 flush 端口（flush 时 valid 清零，丢弃数据）
//   getRobIdx    : 可选。若提供，即开启“整 bundle 选择性 flush”（单 robIdx 比较）：
//                  flush 时仅丢弃 payload 中 robIdx 比 flushBranchRobIdx 更年轻的条目，
//                  保留或放行比其更老或等于该分支的条目。
//   laneFlush    : 可选。若提供，则启用"per-lane validMask 选择性 flush"——
//                  适用于 post-IQ 多 lane payload（IssRR/RREx/ExMem）：bundle 内
//                  各 lane 的 robIdx 互不相同，需逐 lane 与 flushBranchRobIdx 比较，
//                  把"年轻于分支"的 lane validMask 清零；若全部 lane 被清零，则
//                  整 bundle 视为废 bundle 丢弃。函数签名 (payload, branchRobIdx)
//                  ⇒ (maskedPayload, anyLaneAlive)。当 laneFlush 提供时优先生效，
//                  忽略 getRobIdx；getRobIdx/laneFlush 二者择一即可。
// ============================================================================
class BaseDff[T <: Data](
    gen: T,
    supportFlush: Boolean = false,
    getRobIdx: Option[T => UInt] = None,
    laneFlush: Option[(T, UInt) => (T, Bool)] = None
) extends Module {
  val in  = IO(Flipped(Decoupled(gen))) // 输入端（Flipped 使 valid/bits 为 Input，ready 为 Output）
  val out = IO(Decoupled(gen))          // 输出端（valid/bits 为 Output，ready 为 Input）
  val flush = Option.when(supportFlush)(IO(Input(Bool())))  // 可选的 flush 信号
  // 选择性 flush 的“基准分支 robIdx”。仅在 supportFlush && (getRobIdx 或 laneFlush) 提供时生效。
  val flushBranchRobIdx = Option.when(supportFlush && (getRobIdx.nonEmpty || laneFlush.nonEmpty))(
    IO(Input(UInt(mycpu.CPUConfig.robPtrWidth.W)))
  )

  val validReg = RegInit(false.B)                             // 数据是否有效
  val bitsReg  = RegInit(0.U.asTypeOf(chiselTypeOf(in.bits))) // 缓存的数据

  out.valid := validReg
  out.bits  := bitsReg

  in.ready := !validReg || out.ready // 背压逻辑：当前为空 或 下游已取走 → 可以接收新数据

  // 判断 a 是否比 b 更年轻（基于带回绕位的 robPtr：`(a-b)` 最高位为 0 且两者不同 → a 更年轻）。
  private def isYoungerRob(a: UInt, b: UInt): Bool = {
    val w = a.getWidth
    ((a - b)(w - 1) === 0.U) && (a =/= b)
  }

  (flush, getRobIdx, flushBranchRobIdx, laneFlush) match {
    case (Some(flushSig), _, Some(branchIdx), Some(laneFn)) =>
      // ---- TD-C：post-IQ 多 lane payload 的 per-lane 选择性 flush ----
      // 由用户提供的 laneFn 把 "年轻于 branchIdx 的 lane" 在 validMask 中清零，
      // 同时返回 anyLaneAlive。当所有 lane 都被清零时，整个 bundle 当作废 bundle 丢弃。
      val (curMasked, curAnyAlive) = laneFn(bitsReg, branchIdx)
      val (inMasked,  inAnyAlive)  = laneFn(in.bits, branchIdx)

      // killCur：当前 bundle 在 flush 后已无任何存活 lane → 整 bundle 丢弃
      // killIn ：上游传入的 bundle 在 flush 后已无任何存活 lane → 不予接收
      val killCur = flushSig && !curAnyAlive
      val killIn  = flushSig && !inAnyAlive

      val canReceive = !validReg || out.ready || killCur
      in.ready := canReceive

      val acceptNew = in.valid && canReceive && !killIn
      val keepCur   = validReg && !killCur && !out.ready
      when(acceptNew) {
        validReg := true.B
        // 接收新 bundle 时，若同拍 flush，则使用 mask 后版本（年轻 lane 已清零）
        bitsReg  := Mux(flushSig, inMasked, in.bits)
      }.otherwise {
        validReg := keepCur
        // 保留当前 bundle 时，若同拍 flush，则把 bitsReg 替换为 mask 后版本
        when(flushSig && keepCur) {
          bitsReg := curMasked
        }
      }

    case (Some(flushSig), Some(extract), Some(branchIdx), None) =>
      // ---- 单 robIdx 选择性 flush 分支（保留原行为，用于 pre-IQ DFF）----
      val killCur = flushSig && isYoungerRob(extract(bitsReg), branchIdx)
      val killIn  = flushSig && isYoungerRob(extract(in.bits), branchIdx)

      val canReceive = !validReg || out.ready || killCur
      in.ready  := canReceive

      val acceptNew = in.valid && canReceive && !killIn
      val keepCur   = validReg && !killCur && !out.ready
      when(acceptNew) {
        validReg := true.B
        bitsReg  := in.bits
      }.otherwise {
        validReg := keepCur
      }

    case (Some(flushSig), _, _, _) =>
      // ---- 粗粒度 flush（保留原行为：整 flush 清空）----
      when(flushSig) {
        validReg := false.B
      }.elsewhen(in.ready) {
        validReg := in.valid
        when(in.valid) {
          bitsReg := in.bits
        }
      }

    case _ =>
      // ---- 无 flush 端口的常规流水寄存器 ----
      when(in.ready) {
        validReg := in.valid
        when(in.valid) {
          bitsReg := in.bits
        }
      }
  }
}
