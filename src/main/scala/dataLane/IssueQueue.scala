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
//   3. instSeq（指令逻辑年龄）：全局单调递增计数器 nextInstSeq，每分配 N 条指令就 +N
//      - 每个表项记录自己的 instSeq，用于判断新旧关系
//      - 与物理位置完全解耦，物理槽可以乱序分配和释放
//   4. Issue 阶段选择 instSeq 最小（最老）的有效条目进行发射
//   5. 精确回滚：Memory 重定向时清除所有有效条目（IQ 中的指令均为未提交的投机指令）
//
// 核心作用：解耦 4-wide Dispatch 与 Issue/Execute 后端之间的带宽差异
//           承担原 RenameBuffer 的缓冲职责
//           物理槽位乱序管理，为后续乱序选择发射做准备
// ============================================================================

// ---- IssueQueue 内部表项：在 DispatchEntry 基础上增加 instSeq 字段 ----
class IssueQueueEntry extends Bundle {
  val data    = new DispatchEntry // 原始 Dispatch 阶段的指令信息
  val instSeq = UInt(CPUConfig.instSeqWidth.W) // 指令逻辑年龄（全局单调递增）
}

// ---- IssueQueue 分配接口（Dispatch 阶段使用）----
class IQAllocIO extends Bundle {
  val canAlloc = Input(Bool())     // IssueQueue 是否有足够空间
  val request  = Output(UInt(3.W)) // 请求分配的表项数（0~4）
  val idxs     = Input(Vec(4, UInt(log2Ceil(CPUConfig.issueQueueEntries).W))) // 分配的物理槽位索引
}

class IQWriteData extends Bundle {
  val entries    = Vec(4, new DispatchEntry)
  val validCount = UInt(3.W)
  val valid      = Bool() // 本周期是否有有效的 Dispatch 输出
}

// ---- IssueQueue 发射选择接口（Issue 阶段使用）----
// Issue 阶段读取最老的有效条目，并通知 IssueQueue 释放该槽位
class IQIssueIO extends Bundle {
  val valid = Output(Bool())            // 是否有可发射的条目
  val entry = Output(new DispatchEntry) // 最老的有效条目数据
  val fire  = Input(Bool())             // Issue 阶段确认发射（释放该槽位）
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
  val issue = IO(new IQIssueIO)

  // ---- 清空信号（Memory 重定向时使用）----
  val flush = IO(Input(Bool()))

  // ========================================================================
  // 存储阵列：Vec[depth] 的平坦 IssueQueueEntry 数组
  // 空闲物理槽位列表：freeVec 位向量
  // ========================================================================
  val buffer    = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new IssueQueueEntry))))
  val validVec  = RegInit(VecInit(Seq.fill(depth)(false.B))) // 每个槽位是否有效
  val freeVec   = RegInit(VecInit(Seq.fill(depth)(true.B)))  // 初始状态：所有槽位空闲
  val freeCount = PopCount(freeVec)                          // 空闲槽位计数

  // ---- FreeList 分配逻辑（级联 PriorityEncoder）----
  // 从 freeVec 中按优先级选择最多 4 个空闲槽位
  val freeValid = Wire(Vec(4, Bool()))
  val freeIdxs  = Wire(Vec(4, UInt(idxWidth.W)))

  // 第 0 个：从 freeVec 中找第一个空闲槽
  val mask0 = freeVec.asUInt
  freeValid(0) := mask0.orR
  freeIdxs(0)  := PriorityEncoder(mask0)

  // 第 1 个：遮蔽前一个已选中的位，找下一个空闲槽
  val mask1 = mask0 & ~(PriorityEncoderOH(mask0))
  freeValid(1) := mask1.orR
  freeIdxs(1)  := PriorityEncoder(mask1)

  // 第 2 个：遮蔽前两个已选中的位，找下下一个空闲槽
  val mask2 = mask1 & ~(PriorityEncoderOH(mask1))
  freeValid(2) := mask2.orR
  freeIdxs(2)  := PriorityEncoder(mask2)

  // 第 3 个：遮蔽前三个已选中的位，找下下个个空闲槽
  val mask3 = mask2 & ~(PriorityEncoderOH(mask2))
  freeValid(3) := mask3.orR
  freeIdxs(3)  := PriorityEncoder(mask3)

  // ========================================================================
  // nextInstSeq：全局单调递增的指令序号计数器，用于确定指令逻辑年龄
  // ========================================================================
  val nextInstSeq = RegInit(0.U(seqWidth.W))

  // ===================== 分配逻辑（Dispatch 阶段）=====================
  alloc.canAlloc := freeCount >= 4.U // canAlloc 不依赖 request，避免与 Dispatch 形成组合环路
  alloc.idxs := freeIdxs // 返回 FreeList 选中的物理索引

  // ===================== 写入逻辑（Dispatch）=====================
  // Dispatch 确认分派后，将指令数据写入对应的物理槽位
  when(write.valid && !flush) { // 冲刷时不写入
    for (i <- 0 until 4) {
      when(i.U < write.validCount) {
        val physIdx = freeIdxs(i)
        // 写入指令数据
        buffer(physIdx).data    := write.entries(i)
        buffer(physIdx).instSeq := nextInstSeq + i.U // 分配逻辑年龄
        // 将该槽位标记为有效和已占用
        validVec(physIdx) := true.B
        freeVec(physIdx)  := false.B
      }
    }
    nextInstSeq := nextInstSeq + write.validCount // 更新全局 instSeq 计数器
  }

  // ===================== 发射选择逻辑（Issue）=====================
  // 在所有 valid 的条目中，选择 instSeq 最小（最老）的条目
  // 使用 found 标志模式（参照 StoreBuffer 的 Drain 选择逻辑）
  val (issueIdx, issueSeq, issueFound) = (0 until depth).foldLeft(
    (0.U(idxWidth.W), 0.U(seqWidth.W), false.B)
  ) { case ((bestIdx, bestSeq, found), i) =>
    // 当尚未找到任何候选时，第一个有效条目直接胜出
    // 当已有候选时，instSeq 更小的候选胜出（选最老的）
    val better = validVec(i) && (!found || (buffer(i).instSeq < bestSeq))
    (Mux(better, i.U(idxWidth.W), bestIdx),
     Mux(better, buffer(i).instSeq, bestSeq),
     found || validVec(i))
  }

  // 发射输出：最老的有效条目
  issue.valid := issueFound
  issue.entry := buffer(issueIdx).data

  // Issue 阶段确认发射后，释放该槽位
  when(issue.fire && !flush) {
    validVec(issueIdx) := false.B
    freeVec(issueIdx)  := true.B
  }

  // ===================== 回滚逻辑（Memory 重定向）=====================
  // flush 时清除所有有效条目，重置 instSeq 计数器
  // IssueQueue 中的所有指令均为未提交的投机指令，重定向时全部丢弃
  when(flush) {
    for (i <- 0 until depth) {
      validVec(i) := false.B
      freeVec(i)  := true.B
    }
    nextInstSeq := 0.U
  }
}
