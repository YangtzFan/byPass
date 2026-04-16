package mycpu.device

import chisel3._
import chisel3.util._
import mycpu.CPUConfig
import mycpu.dataLane._

// ============================================================================
// ROB（重排序缓冲区）—— 无寄存器重命名版本
// ============================================================================
// 变更：
//   - 移除所有物理寄存器映射字段（pdst/stalePdst/ldst/hasCheckpoint）
//   - 分配数据和提交接口均只使用逻辑寄存器 rd
//   - 分支恢复只回滚 ROB tail，不恢复任何 checkpoint
// ============================================================================
class ROB(val entries: Int = CPUConfig.robEntries) extends Module {
  val idxW = CPUConfig.robIdxWidth  // 索引位宽（7）

  val alloc    = IO(Flipped(new ROBMultiAllocIO)) // Dispatch 4-wide 分配接口
  val rollback = IO(new Bundle {                  // Memory 阶段回滚接口
    val valid  = Input(Bool())
    val robIdx = Input(UInt(CPUConfig.robPtrWidth.W))
  })
  val refresh = IO(Flipped(new ROBRefreshIO))     // Refresh 阶段完成标记接口
  val commit  = IO(new ROBCommitIO)               // 提交接口

  // 存储阵列和指针
  val rob = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new ROBEntry))))
  val head = RegInit(0.U((idxW + 1).W))   // 头指针（最老的指令）
  val tail = RegInit(0.U((idxW + 1).W))   // 尾指针（下一个空位）
  private def idx(ptr: UInt) = ptr(idxW - 1, 0)

  val count = tail - head
  val empty = count === 0.U
  val full  = count === entries.U

  alloc.canAlloc := count +& 4.U <= entries.U
  for (i <- 0 until 4) {
    alloc.idxs(i) := tail + i.U
  }

  val doAlloc = alloc.request > 0.U && !rollback.valid

  // ===================== 提交输出（组合逻辑，始终驱动）=====================
  val headEntry = rob(idx(head))
  val canCommit = !empty && headEntry.done
  commit.valid        := canCommit
  commit.pc           := headEntry.pc
  commit.inst         := headEntry.inst
  commit.rd           := headEntry.rd
  commit.regWen       := headEntry.regWen && canCommit
  commit.regWBData    := headEntry.regWBData
  commit.isStore      := headEntry.isStore
  commit.storeSeq     := headEntry.storeSeq
  commit.isBranch     := headEntry.isBranch
  commit.isJump       := headEntry.isJump
  commit.mispredict   := headEntry.mispredict && canCommit
  commit.actualTaken  := headEntry.actualTaken
  commit.actualTarget := headEntry.actualTarget
  commit.predictTaken := headEntry.predictTaken
  commit.bhtMeta      := headEntry.bhtMeta

  // ===================== Memory 阶段回滚逻辑 =====================
  when(rollback.valid) {
    tail := rollback.robIdx + 1.U
  }.otherwise {
    // ===================== 4-wide 分配逻辑 =====================
    when(doAlloc) {
      for (i <- 0 until 4) {
        when(i.U < alloc.request) {
          val entry = rob(idx(tail + i.U))
          entry.done          := false.B
          entry.pc            := alloc.data(i).pc
          entry.inst          := alloc.data(i).inst
          entry.rd            := alloc.data(i).rd
          entry.regWen        := alloc.data(i).regWen
          entry.regWBData     := 0.U
          entry.isLoad        := alloc.data(i).isLoad
          entry.isStore       := alloc.data(i).isStore
          entry.storeSeq      := alloc.data(i).storeSeq
          entry.isBranch      := alloc.data(i).isBranch
          entry.isJump        := alloc.data(i).isJump
          entry.predictTaken  := alloc.data(i).predictTaken
          entry.predictTarget := alloc.data(i).predictTarget
          entry.actualTaken   := false.B
          entry.actualTarget  := 0.U
          entry.mispredict    := false.B
          entry.exception     := false.B
          entry.bhtMeta       := alloc.data(i).bhtMeta
        }
      }
      tail := tail + alloc.request
    }
  }
  // ===================== Refresh 阶段完成标记 =====================
  when(refresh.valid) {
    val refEntry = rob(idx(refresh.idx))
    refEntry.done         := true.B
    refEntry.regWBData    := refresh.regWBData
    refEntry.actualTaken  := refresh.actualTaken
    refEntry.actualTarget := refresh.actualTarget
    refEntry.mispredict   := refresh.mispredict
  }
  // ===================== Commit 指针更新 =====================
  when(canCommit) {
    head := head + 1.U
  }
}
