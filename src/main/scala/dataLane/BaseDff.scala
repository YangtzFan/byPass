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
//   getRobIdx    : 可选。若提供，即开启“精确选择性 flush”：
//                  flush 时仅丢弃 payload 中 robIdx 比 flushBranchRobIdx 更年轻的条目，
//                  保留或放行比其更老或等于该分支的条目。用于 OoO 单发射场景下，
//                  防止老于误预测分支但因 OoO 在其后进入流水级的指令被误清除。
// ============================================================================
class BaseDff[T <: Data](
    gen: T,
    supportFlush: Boolean = false,
    getRobIdx: Option[T => UInt] = None
) extends Module {
  val in  = IO(Flipped(Decoupled(gen))) // 输入端（Flipped 使 valid/bits 为 Input，ready 为 Output）
  val out = IO(Decoupled(gen))          // 输出端（valid/bits 为 Output，ready 为 Input）
  val flush = Option.when(supportFlush)(IO(Input(Bool())))  // 可选的 flush 信号
  // 选择性 flush 的“基准分支 robIdx”。仅在 supportFlush && getRobIdx 同时提供时生效。
  val flushBranchRobIdx = Option.when(supportFlush && getRobIdx.nonEmpty)(
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

  (flush, getRobIdx, flushBranchRobIdx) match {
    case (Some(flushSig), Some(extract), Some(branchIdx)) =>
      // ---- 精确选择性 flush 分支 ----
      // killCur：当前缓存的指令比误预测分支更年轻，本拍应丢弃
      // killIn ：上游传入的指令比误预测分支更年轻，本拍不应接收
      val killCur = flushSig && isYoungerRob(extract(bitsReg), branchIdx)
      val killIn  = flushSig && isYoungerRob(extract(in.bits), branchIdx)

      // 若当前指令将被 flush 丢弃，则本拍等效腾出空位，可直接接收上游数据。
      // 注：out.valid 不做掩码（保留 validReg 驱动），避免与 flushSig 形成组合环；
      //     下游自身的选择性 flush 会在同拍按 robIdx 精确丢弃“年轻于分支”的数据。
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

    case (Some(flushSig), _, _) =>
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
