package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// ROBEntry —— ROB 中每个表项存储的字段
// ============================================================================
class ROBEntry extends Bundle {
  val done            = Bool()           // 该指令是否已执行完毕（WB 标记）
  val pc              = UInt(32.W)       // 指令 PC（用于调试和 flush 地址计算）
  val inst            = UInt(32.W)       // 原始指令（调试用）
  val rd              = UInt(5.W)        // 目标寄存器编号
  val regWriteEnable  = Bool()           // 是否需要写回寄存器
  val result          = UInt(32.W)       // 计算结果
  val isLoad          = Bool()           // 是否为 Load 指令
  val isStore         = Bool()           // 是否为 Store 指令
  val isBranch        = Bool()           // 是否为分支指令
  val isJump          = Bool()           // 是否为 JAL/JALR
  val predict_taken   = Bool()           // Decode 阶段的预测结果
  val predict_target  = UInt(32.W)       // 预测目标地址
  val actual_taken    = Bool()           // Execute 阶段计算出的实际跳转结果
  val actual_target   = UInt(32.W)       // 实际跳转目标
  val mispredict      = Bool()           // 是否预测错误
  val exception       = Bool()           // 是否有异常（预留）
  val store_addr      = UInt(32.W)       // Store 地址
  val store_data      = UInt(32.W)       // Store 数据
  val store_mask      = UInt(3.W)        // Store 宽度掩码
  val bht_meta        = UInt(2.W)        // BHT 状态快照
}

// ============================================================================
// ROB（重排序缓冲区）—— 保证顺序提交
// ============================================================================
// 核心思想：
//   - 指令在 Dispatch 阶段按程序顺序**分配** ROB 表项（写入 tail）
//   - 指令可以乱序**执行完成**（WB 用 robIdx 标记 done）
//   - 指令只能从 **head** 按顺序**提交**（head 必须 valid && done）
//   - 如果 head 的指令是 mispredict，在提交时触发 flush
//
// 结构：环形队列，head 指向最老的指令，tail 指向下一个空位
// 当前实现：单分配（每周期最多分配 1 条）、单提交（每周期最多提交 1 条）
// ============================================================================
class ROB(val entries: Int = CPUConfig.robEntries) extends Module {
  val idxW = CPUConfig.robIdxWidth  // 索引位宽

  val io = IO(new Bundle {
    val alloc  = Flipped(new ROBAllocIO) // Dispatch 分配接口（使用打包 Bundle）
    val wb     = Flipped(new ROBWbIO) // WB 完成标记接口（使用打包 Bundle）
    val commit = new ROBCommitIO // 提交（Commit）接口（使用打包 Bundle）
    val flush  = new ROBFlushIO // Flush 接口（使用打包 Bundle）
    // ---- 状态观测 ----
    val empty  = Output(Bool()) // ROB 为空
    val full   = Output(Bool()) // ROB 已满
  })

  // 存储阵列和指针
  val rob = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new ROBEntry))))
  private val ptrWidth = idxW + 1 // head/tail 指针比实际索引多 1 位，用最高位区分是否多绕一圈
  val head = RegInit(0.U(ptrWidth.W)) // 头指针（最老的指令）
  val tail = RegInit(0.U(ptrWidth.W)) // 尾指针（下一个空位）
  private def idx(ptr: UInt) = ptr(idxW - 1, 0) // 访问实际索引 = 指针低 idxW 位
  val count = tail - head // 从指针差推导有效表项数（无需额外寄存器）

  val empty = count === 0.U // 读空信号
  val full  = count === entries.U // 写满信号
  io.empty := empty
  io.full  := full
  io.alloc.ready := !full     // 有空位时允许分配
  io.alloc.idx   := idx(tail) // 分配到 tail 位置

  val tailEntry = rob(idx(tail))
  val headEntry = rob(idx(head))
  val writeBackEntry = rob(io.wb.idx)

  val canAlloc  = io.alloc.valid && !full
  val canCommit = !empty && headEntry.done
  val commitMispredict = canCommit && headEntry.mispredict
  io.flush.valid := commitMispredict
  io.flush.addr := headEntry.actual_target  // 正确的跳转目标

  // ===================== 提交输出（组合逻辑，始终驱动）=====================
  io.commit.valid      := canCommit
  io.commit.pc         := headEntry.pc
  io.commit.inst       := headEntry.inst
  io.commit.rd         := headEntry.rd
  io.commit.regWen     := headEntry.regWriteEnable && canCommit
  io.commit.result     := headEntry.result
  io.commit.isStore    := headEntry.isStore
  io.commit.storeAddr  := headEntry.store_addr
  io.commit.storeData  := headEntry.store_data
  io.commit.storeMask  := headEntry.store_mask
  io.commit.isBranch   := headEntry.isBranch
  io.commit.isJump     := headEntry.isJump
  io.commit.mispredict := headEntry.mispredict && canCommit
  io.commit.actualTaken  := headEntry.actual_taken
  io.commit.actualTarget := headEntry.actual_target
  io.commit.predictTaken := headEntry.predict_taken
  io.commit.bhtMeta      := headEntry.bht_meta

  // ===================== Flush 逻辑 =====================
  // 当提交的指令是 mispredict 时，触发 flush，重定向 PC 到正确地址
  when(commitMispredict) { // Flush：清空所有表项，重置 ROB
    for (i <- 0 until entries) {
      rob(i).done  := false.B
    }
    head := 0.U
    tail := 0.U
  }.otherwise { // 正常操作：分配移动 tail，提交移动 head
    // ===================== 分配逻辑 =====================
    when(canAlloc) { // 将指令信息写入 tail 位置
      tailEntry.done           := false.B     // 刚分配，尚未执行完成
      tailEntry.pc             := io.alloc.pc
      tailEntry.inst           := io.alloc.inst
      tailEntry.rd             := io.alloc.rd
      tailEntry.regWriteEnable := io.alloc.regWen
      tailEntry.result         := 0.U
      tailEntry.isLoad         := io.alloc.isLoad
      tailEntry.isStore        := io.alloc.isStore
      tailEntry.isBranch       := io.alloc.isBranch
      tailEntry.isJump         := io.alloc.isJump
      tailEntry.predict_taken  := io.alloc.predictTaken
      tailEntry.predict_target := io.alloc.predictTarget
      tailEntry.actual_taken   := false.B
      tailEntry.actual_target  := 0.U
      tailEntry.mispredict     := false.B
      tailEntry.exception      := false.B
      tailEntry.store_addr     := 0.U
      tailEntry.store_data     := 0.U
      tailEntry.store_mask     := 0.U
      tailEntry.bht_meta       := io.alloc.bhtMeta
      tail := tail + 1.U
    }
    // ===================== WB 完成标记 =====================
    when(io.wb.valid) {
      writeBackEntry.done           := true.B
      writeBackEntry.result         := io.wb.result
      writeBackEntry.actual_taken   := io.wb.actualTaken
      writeBackEntry.actual_target  := io.wb.actualTarget
      writeBackEntry.mispredict     := io.wb.mispredict
      writeBackEntry.store_addr     := io.wb.storeAddr
      writeBackEntry.store_data     := io.wb.storeData
      writeBackEntry.store_mask     := io.wb.storeMask
    }
    // ===================== 提交指针更新（时序逻辑）=====================
    when(canCommit) {
      head := head + 1.U
    }
  }
}
