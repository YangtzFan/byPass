package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// ROB（重排序缓冲区）—— 支持 4-wide 分配 + Memory 阶段回滚
// ============================================================================
// 变更：
//   - 分配接口由 Dispatch 阶段驱动（原由 Rename 驱动）
//   - 完成标记接口改为 ROBRefreshIO（Refresh 阶段标记完成）
//   - 移除 Store 相关字段（Store 信息由 StoreBuffer 管理）
//   - Commit 接口不再输出 Store 地址/数据/掩码
// ============================================================================
class ROB(val entries: Int = CPUConfig.robEntries) extends Module {
  val idxW = CPUConfig.robIdxWidth  // 索引位宽（7）

  val io = IO(new Bundle { // 状态观测
    val empty = Output(Bool())
    val full  = Output(Bool())
  })
  val alloc    = IO(Flipped(new ROBMultiAllocIO)) // Dispatch 4-wide 分配接口
  val rollback = IO(new Bundle {                  // Memory 阶段回滚接口
    val valid  = Input(Bool())                        // 回滚使能
    val robIdx = Input(UInt(CPUConfig.robPtrWidth.W)) // 误预测指令的 ROB 指针
  })
  val refresh = IO(Flipped(new ROBRefreshIO))     // Refresh 阶段完成标记接口
  val commit  = IO(new ROBCommitIO)               // 提交接口

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

  // canAlloc 不依赖 request，避免与 Dispatch 形成组合环路
  // 只要空闲空间 >= 4（最大发射宽度），即可分配
  alloc.canAlloc := count +& 4.U <= entries.U
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
  commit.regWBData    := headEntry.regWBData
  commit.isStore      := headEntry.isStore
  commit.isBranch     := headEntry.isBranch
  commit.isJump       := headEntry.isJump
  commit.mispredict   := headEntry.mispredict && canCommit
  commit.actualTaken  := headEntry.actual_taken
  commit.actualTarget := headEntry.actual_target
  commit.predictTaken := headEntry.predict_taken
  commit.bhtMeta      := headEntry.bht_meta

  // ===================== Memory 阶段回滚逻辑 =====================
  // 当 Memory 检测到分支预测错误时，将 tail 回滚到误预测指令的下一位，
  // 丢弃所有年轻的错误路径表项。误预测指令本身保留（等待 Refresh 标记 done 后正常提交）。
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
          entry.regWBData      := 0.U
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
          entry.bht_meta       := alloc.data(i).bhtMeta
        }
      }
      tail := tail + alloc.request
    }
  }

  // ===================== Refresh 阶段完成标记 =====================
  when(refresh.valid) {
    val refEntry = rob(idx(refresh.idx))
    refEntry.done          := true.B
    refEntry.regWBData     := refresh.regWBData
    refEntry.actual_taken  := refresh.actualTaken
    refEntry.actual_target := refresh.actualTarget
    refEntry.mispredict    := refresh.mispredict
  }

  // ===================== 提交指针更新 =====================
  when(canCommit) {
    head := head + 1.U
  }
}
