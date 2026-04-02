package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// ROB（重排序缓冲区）—— 支持 4-wide 分配 + MEM 阶段回滚
// ============================================================================
// 核心变更（相比之前）：
//   - 分配接口从单发 ROBAllocIO 改为 4-wide ROBMultiAllocIO（支持 Rename 4 条/周期）
//   - 新增 MEM 阶段回滚接口：MEM 检测到预测错误时，回滚 tail 到误预测指令之后，
//     丢弃所有年轻的错误路径表项
//   - 移除 Commit 阶段 flush 逻辑（重定向已由 MEM 阶段处理）
// ============================================================================
class ROB(val entries: Int = CPUConfig.robEntries) extends Module {
  val idxW = CPUConfig.robIdxWidth  // 索引位宽（7）

  val io = IO(new Bundle { // 状态观测
    val empty = Output(Bool())
    val full  = Output(Bool())
  })
  val alloc    = IO(Flipped(new ROBMultiAllocIO)) // Rename 4-wide 分配接口
  val rollback = IO(new Bundle {            // MEM 阶段回滚接口
    val valid  = Input(Bool())                        // 回滚使能
    val robIdx = Input(UInt(CPUConfig.robPtrWidth.W)) // 误预测指令的 ROB 指针
  })
  val wb     = IO(Flipped(new ROBWbIO))         // WB 完成标记接口
  val commit = IO(new ROBCommitIO)              // 提交接口

  // 存储阵列和指针
  val rob = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new ROBEntry))))
  private val ptrWidth = idxW + 1
  val head = RegInit(0.U(ptrWidth.W))   // 头指针（最老的指令）
  val tail = RegInit(0.U(ptrWidth.W))   // 尾指针（下一个空位）
  private def idx(ptr: UInt) = ptr(idxW - 1, 0)

  val count = tail - head
  val empty = count === 0.U
  val full  = count === entries.U
  io.empty := empty
  io.full  := full

  alloc.canAlloc := entries.U - count >= alloc.request // 检查剩余空间是否足够分配 request 条表项
  for (i <- 0 until 4) { // 返回连续的 ROB 指针（从当前 tail 开始）
    alloc.idxs(i) := tail + i.U
  }

  // 用于判断是否真正进行分配（request > 0 且空间充足且无回滚）
  val doAlloc = alloc.request > 0.U && alloc.canAlloc && !rollback.valid

  val headEntry = rob(idx(head))
  val canCommit = !empty && headEntry.done

  // ===================== 提交输出（组合逻辑，始终驱动）=====================
  commit.valid        := canCommit
  commit.pc           := headEntry.pc
  commit.inst         := headEntry.inst
  commit.rd           := headEntry.rd
  commit.regWen       := headEntry.regWriteEnable && canCommit
  commit.result       := headEntry.result
  commit.isStore      := headEntry.isStore
  commit.storeAddr    := headEntry.store_addr
  commit.storeData    := headEntry.store_data
  commit.storeMask    := headEntry.store_mask
  commit.isBranch     := headEntry.isBranch
  commit.isJump       := headEntry.isJump
  commit.mispredict   := headEntry.mispredict && canCommit
  commit.actualTaken  := headEntry.actual_taken
  commit.actualTarget := headEntry.actual_target
  commit.predictTaken := headEntry.predict_taken
  commit.bhtMeta      := headEntry.bht_meta

  // ===================== MEM 阶段回滚逻辑 =====================
  // 当 MEM 检测到分支预测错误时，将 tail 回滚到误预测指令的下一位，
  // 丢弃所有年轻的错误路径表项。误预测指令本身保留（等待 WB 标记 done 后正常提交）。
  when(rollback.valid) {
    tail := rollback.robIdx + 1.U
  }.otherwise {
    // ===================== 4-wide 分配逻辑 =====================
    when(doAlloc) {
      for (i <- 0 until 4) {
        when(i.U < alloc.request) {
          val allocPtr = tail + i.U
          val entry = rob(idx(allocPtr))
          entry.done           := false.B
          entry.pc             := alloc.data(i).pc
          entry.inst           := alloc.data(i).inst
          entry.rd             := alloc.data(i).rd
          entry.regWriteEnable := alloc.data(i).regWen
          entry.result         := 0.U
          entry.isLoad         := alloc.data(i).isLoad
          entry.isStore        := alloc.data(i).isStore
          entry.isBranch       := alloc.data(i).isBranch
          entry.isJump         := alloc.data(i).isJump
          entry.predict_taken  := alloc.data(i).predictTaken
          entry.predict_target := alloc.data(i).predictTarget
          entry.actual_taken   := false.B
          entry.actual_target  := 0.U
          entry.mispredict     := false.B
          entry.exception      := false.B
          entry.store_addr     := 0.U
          entry.store_data     := 0.U
          entry.store_mask     := 0.U
          entry.bht_meta       := alloc.data(i).bhtMeta
        }
      }
      tail := tail + alloc.request
    }
  }

  // ===================== WB 完成标记 =====================
  when(wb.valid) {
    val wbEntry = rob(idx(wb.idx))
    wbEntry.done          := true.B
    wbEntry.result        := wb.result
    wbEntry.actual_taken  := wb.actualTaken
    wbEntry.actual_target := wb.actualTarget
    wbEntry.mispredict    := wb.mispredict
    wbEntry.store_addr    := wb.storeAddr
    wbEntry.store_data    := wb.storeData
    wbEntry.store_mask    := wb.storeMask
  }

  // ===================== 提交指针更新 =====================
  when(canCommit) {
    head := head + 1.U
  }
}
