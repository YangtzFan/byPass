package mycpu.device

import chisel3._
import chisel3.util._
import mycpu.CPUConfig
import mycpu.dataLane._

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

  val alloc    = IO(Flipped(new ROBMultiAllocIO)) // Dispatch 4-wide 分配接口
  val rollback = IO(new Bundle {                  // Memory 阶段回滚接口
    val valid  = Input(Bool())                        // 回滚使能
    val robIdx = Input(UInt(CPUConfig.robPtrWidth.W)) // 误预测指令的 ROB 指针
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

  alloc.canAlloc := count +& 4.U <= entries.U // canAlloc 不依赖 request，避免与 Dispatch 形成组合环路
  for (i <- 0 until 4) { // 返回连续的 ROB 指针（从当前 tail 开始）
    alloc.idxs(i) := tail + i.U
  }

  val doAlloc = alloc.request > 0.U && !rollback.valid // 判断是否真正进行分配（request > 0 且无回滚）

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
  commit.storeSeq     := headEntry.storeSeq   // ROB 提交时传递 Store 的 storeSeq 给 StoreBuffer
  commit.isBranch     := headEntry.isBranch
  commit.isJump       := headEntry.isJump
  commit.hasCheckpoint := headEntry.hasCheckpoint  // 传递 checkpoint 标记，用于 BCT 释放
  commit.mispredict   := headEntry.mispredict && canCommit
  commit.actualTaken  := headEntry.actualTaken
  commit.actualTarget := headEntry.actualTarget
  commit.predictTaken := headEntry.predictTaken
  commit.bhtMeta      := headEntry.bhtMeta
  // 物理寄存器映射信息：Commit 时用于更新 RRAT 和释放 stalePdst
  commit.pdst         := headEntry.pdst
  commit.stalePdst    := headEntry.stalePdst
  commit.ldst         := headEntry.ldst

  // ===================== Memory 阶段回滚逻辑 =====================
  // 当 Memory 检测到分支预测错误时，tail 回滚到误预测指令的下一位，丢弃所有年轻表项，误预测指令本身保留
  when(rollback.valid) {
    tail := rollback.robIdx + 1.U
  }.otherwise {
    // ===================== 4-wide 分配逻辑 =====================
    when(doAlloc) {
      for (i <- 0 until 4) {
        when(i.U < alloc.request) { // FetchBuffer 确保有效指令优先占有低位
          val entry = rob(idx(tail + i.U)) // 分配下一表项
          entry.done          := false.B
          entry.pc            := alloc.data(i).pc
          entry.inst          := alloc.data(i).inst
          entry.rd            := alloc.data(i).rd
          entry.regWen        := alloc.data(i).regWen
          entry.regWBData     := 0.U
          entry.isLoad        := alloc.data(i).isLoad
          entry.isStore       := alloc.data(i).isStore
          entry.storeSeq      := alloc.data(i).storeSeq  // 写入 Store 的逻辑年龄（Commit 时传给 StoreBuffer 用于定位表项）
          entry.isBranch      := alloc.data(i).isBranch
          entry.isJump        := alloc.data(i).isJump
          entry.hasCheckpoint := alloc.data(i).hasCheckpoint  // 标记该指令是否保存了 BCT checkpoint
          entry.predictTaken  := alloc.data(i).predictTaken
          entry.predictTarget := alloc.data(i).predictTarget
          entry.actualTaken   := false.B
          entry.actualTarget  := 0.U
          entry.mispredict    := false.B
          entry.exception     := false.B
          entry.bhtMeta       := alloc.data(i).bhtMeta
          // 物理寄存器映射信息存入 ROB 表项
          entry.pdst          := alloc.data(i).pdst
          entry.stalePdst     := alloc.data(i).stalePdst
          entry.ldst          := alloc.data(i).ldst
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
