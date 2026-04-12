package mycpu.device

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// FreeList —— 空闲物理寄存器管理（FIFO 结构）
// ============================================================================
// 管理空闲物理寄存器的分配和释放。
// 初始状态：p32~p127 空闲（共 96 个），p0~p31 已分配给初始映射
// p0 永远不能被分配（p0 硬编码为 0，永远映射到 x0）
//
// 端口配置：
//   - 分配端口：Rename 阶段请求 0~4 个物理寄存器
//   - 释放端口：Commit 阶段释放 stalePdst
//   - 恢复端口：分支预测失败时恢复 FreeList 状态
//
// FIFO 实现：使用环形缓冲区管理空闲物理寄存器编号
// ============================================================================
class RenameIO extends Bundle {
  val allocReq   = Output(UInt(3.W))                             // 请求分配数量（0~4）为 0 时相当于不分配
  val canAlloc   = Input(Bool())                                 // 是否有足够空闲寄存器（>= 4）
  val allocPdst  = Input(Vec(4, UInt(CPUConfig.prfAddrWidth.W))) // 分配的物理寄存器编号
  val doAlloc    = Output(Bool())                                // 实际提交分配（Rename 确认后拉高）
  val snapAllocReq  = Output(UInt(3.W)) // 第一个 checkpoint 中需要计入的 FreeList 分配数量
  val snapAllocReq2 = Output(UInt(3.W)) // 第二个 checkpoint 中需要计入的 FreeList 分配数量
}

class FreeList extends Module {
  // ---- Rename 阶段端口 ----
  val renameIO = IO(Flipped(new RenameIO))

  val io = IO(new Bundle {
    // ---- Commit 端口 ----
    val freeValid  = Input(Bool())                         // 释放使能
    val freePdst   = Input(UInt(CPUConfig.prfAddrWidth.W)) // 待释放的物理寄存器编号

    // ---- 恢复端口（分支预测失败时恢复 FreeList 状态）----
    val recover      = Input(Bool())                                       // 恢复使能
    val recoverHead  = Input(UInt((log2Ceil(CPUConfig.freeListEntries) + 1).W))  // 恢复的 head 指针
    val recoverTail  = Input(UInt((log2Ceil(CPUConfig.freeListEntries) + 1).W))  // 恢复的 tail 指针

    // ---- Checkpoint 读取端口（Rename 阶段保存 checkpoint 用）----
    // snapAllocReq：第一个 checkpoint 中实际需要计入的分配数量（仅第一个分支及之前的 lane）
    // snapAllocReq2：第二个 checkpoint 中实际需要计入的分配数量（仅第二个分支及之前的 lane）
    // 与 allocReq（全组分配数）不同，用于精确计算 checkpoint 的 head 快照

    val snapHead  = Output(UInt((log2Ceil(CPUConfig.freeListEntries) + 1).W))  // 第一个 checkpoint head 快照
    val snapHead2 = Output(UInt((log2Ceil(CPUConfig.freeListEntries) + 1).W))  // 第二个 checkpoint head 快照
    val snapTail  = Output(UInt((log2Ceil(CPUConfig.freeListEntries) + 1).W))  // 当前 tail 指针快照
  })

  val numFree = CPUConfig.freeListEntries  // 实际空闲物理寄存器数量（96）
  // FIFO 物理容量必须是 2 的幂，以保证位掩码取模索引的正确性
  // 96 不是 2 的幂，log2Ceil(96)=7 对应范围 0~127，直接位截取会越界
  // 因此将 FIFO 容量上舍入到 2^7 = 128，多出的 32 个槽位不影响逻辑正确性
  val depth = 1 << log2Ceil(numFree)      // 128（2 的幂）
  val ptrW  = log2Ceil(depth) + 1         // 指针位宽（含回绕位）= 8
  val idxW  = log2Ceil(depth)             // 索引位宽 = 7

  // FIFO 存储：前 96 个槽位初始化为 p32~p127，多余的 32 个槽位初始化为 0（不会被读到）
  val fifo = RegInit(VecInit((0 until depth).map { i =>
    if (i < numFree) (i + CPUConfig.archRegs).U(CPUConfig.prfAddrWidth.W)
    else 0.U(CPUConfig.prfAddrWidth.W)
  }))
  val head = RegInit(0.U(ptrW.W))             // 分配指针（出队端）
  val tail = RegInit(numFree.U(ptrW.W))       // 释放指针（入队端），初始指向 96 表示全满

  // 位截取取模：ptr(6,0) 在容量为 128 时等价于 ptr % 128，不会越界
  private def idx(ptr: UInt) = ptr(idxW - 1, 0)
  val count = tail - head  // 当前空闲数

  // ---- 分配逻辑 ----
  // 只要空闲数 >= 4 就允许分配（不依赖 allocReq 避免组合环路）
  renameIO.canAlloc := count >= 4.U

  // 输出连续 4 个空闲物理寄存器编号（从 head 开始，不管是否实际分配）
  for (i <- 0 until 4) {
    renameIO.allocPdst(i) := fifo(idx(head + i.U))
  }

  // 实际分配时推进 head
  when(io.recover) {
    // ---- 分支恢复：仅恢复 head 指针 ----
    // 不恢复 tail：已提交指令释放的物理寄存器应保留在空闲列表中
    // 否则每次 mispredict 恢复都会泄漏已释放的物理寄存器
    head := io.recoverHead
  }.otherwise {
    when(renameIO.doAlloc) {
      head := head + renameIO.allocReq
    }
  }
  // ---- 释放逻辑：独立于恢复逻辑 ----
  // Commit 释放 stalePdst 可以与分支恢复同周期发生（Commit 的指令比 mispredict 更老，释放合法）
  // p0 不能被释放（p0 硬编码为 0，永远映射到 x0）
  when(io.freeValid && io.freePdst =/= 0.U) {
    fifo(idx(tail)) := io.freePdst
    tail := tail + 1.U
  }

  // ---- Checkpoint 输出 ----
  // snapHead 使用 snapAllocReq（仅第一个分支及之前 lane 的分配数）
  // snapHead2 使用 snapAllocReq2（仅第二个分支及之前 lane 的分配数）
  // 恢复时，对应 checkpoint 之后 lane 分配的物理寄存器会回到空闲池
  io.snapHead  := Mux(renameIO.doAlloc, head + renameIO.snapAllocReq, head)
  io.snapHead2 := Mux(renameIO.doAlloc, head + renameIO.snapAllocReq2, head)
  io.snapTail  := tail
}
