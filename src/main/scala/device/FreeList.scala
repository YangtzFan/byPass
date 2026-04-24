package mycpu.device

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// FreeList —— 空闲物理寄存器管理（FIFO 结构）
// ============================================================================
// 管理空闲物理寄存器的分配和释放。
// 初始状态：p32~p127 空闲（共 96 个），p0~p31 已分配给初始 RAT 映射
// p0 永远不能被分配（p0 硬编码为 0，永远映射到 x0）
//
// 端口配置：
//   - 分配端口：Rename 阶段请求 0~4 个物理寄存器
//   - 释放端口：Commit 阶段释放 stalePdst（旧的物理寄存器映射）
//   - 恢复端口：分支预测失败时恢复 FreeList 的 head 指针
//
// FIFO 实现细节：
//   - 使用 128 项环形缓冲区（2的幂），便于指针位截取索引
//   - 实际最多存储 96 个有效表项（初始 p32~p127）
//   - 多出的 32 个槽位作为 FIFO 循环的缓冲空间
//   - 有效区间为 [head, tail)，通过 count = tail - head 计算空闲数
//   - 分支恢复时仅恢复 head（已提交的释放操作保持不变）
// ============================================================================
class RenameIO extends Bundle {
  val canAlloc        = Input(Bool())     // 是否有足够空闲寄存器（>= allocReq 预览值）
  val doAlloc         = Output(Bool())    // 实际提交分配（Rename 确认后拉高）
  val allocReq        = Output(UInt(3.W)) // 请求分配数量（0~4），为 0 时相当于不分配
  val allocPdst       = Input(Vec(4, UInt(CPUConfig.prfAddrWidth.W))) // 分配的物理寄存器编号
  val snapAllocReq1   = Output(UInt(3.W)) // 第一个 checkpoint 中需要计入的 FreeList 分配数量
  val snapAllocReq2   = Output(UInt(3.W)) // 第二个 checkpoint 中需要计入的 FreeList 分配数量
}

class FreeList extends Module {
  // ---- Rename 阶段端口 ----
  val renameIO = IO(Flipped(new RenameIO))

  val io = IO(new Bundle {
    // ---- Commit 端口 ----
    // commitWidth 个独立释放端口（每 lane 一个 stalePdst）。多 lane 同拍都释放时
    // tail 按有效个数推进，FIFO 中连续写入。乱序下 stalePdst 乱序提交但 FreeList
    // 只是“归还池子”，顺序无关紧要。
    val freeValid  = Input(Vec(CPUConfig.commitWidth, Bool()))                         // 各 lane 释放使能
    val freePdst   = Input(Vec(CPUConfig.commitWidth, UInt(CPUConfig.prfAddrWidth.W))) // 各 lane 待释放的物理寄存器编号

    // ---- 恢复端口（分支预测失败时恢复 FreeList 状态）----
    // 只恢复 head：已提交指令释放的物理寄存器应留在空闲池里，不恢复 tail
    val recover      = Input(Bool())
    val recoverHead  = Input(UInt((log2Ceil(CPUConfig.freeListEntries) + 1).W))

    // ---- Checkpoint 读取端口（Rename 阶段保存 checkpoint 用）----
    val snapHead1 = Output(UInt((log2Ceil(CPUConfig.freeListEntries) + 1).W))
    val snapHead2 = Output(UInt((log2Ceil(CPUConfig.freeListEntries) + 1).W))
  })

  val numFree = CPUConfig.freeListEntries  // 实际空闲物理寄存器数量（96）
  // FIFO 物理容量必须是 2 的幂，以保证位掩码取模索引的正确性
  // 96 不是 2 的幂，log2Ceil(96)=7 对应范围 0~127，直接位截取会越界
  // 因此将 FIFO 容量上舍入到 2^7 = 128，多出的 32 个槽位不影响逻辑正确性
  val idxW  = log2Ceil(numFree) // 索引位宽 = 7
  val ptrW  = idxW + 1  // 指针位宽（含回绕位）= 8
  val depth = 1 << idxW // 128（2 的幂）

  // FIFO 存储：前 96 个槽位初始化为 p32~p127，多余的 32 个槽位初始化为 0
  val fifo = RegInit(VecInit((0 until depth).map { i =>
    if (i < numFree) (i + 32).U(CPUConfig.prfAddrWidth.W)
    else 0.U(CPUConfig.prfAddrWidth.W)
  }))
  val head = RegInit(0.U(ptrW.W))       // 分配指针（出队端）
  val tail = RegInit(numFree.U(ptrW.W)) // 释放指针（入队端），初始指向 96 表示全空闲
  private def idx(ptr: UInt) = ptr(idxW - 1, 0)
  val count = tail - head  // 当前空闲数

  // ---- 分配逻辑 ----
  // 只有空闲数 >= 4 就允许分配（不依赖 allocReq 避免组合环路）
  renameIO.canAlloc := count >= 4.U
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
  for (i <- 0 until 4) { // 组合逻辑输出连续 4 个空闲物理寄存器编号（不管是否实际分配）
    renameIO.allocPdst(i) := fifo(idx(head + i.U))
  }

  // ---- 释放逻辑：独立于恢复逻辑 ----
  // Commit 释放 stalePdst 可以与分支恢复同周期发生（Commit 的指令比 mispredict 更老，释放合法）
  // 多 lane 同拍释放时：
  //   1. 各 lane 的 stalePdst 都可能是有效释放（排除 p0）；
  //   2. 使用前缀计数 (PrefixCount) 为每 lane 选择对应的 FIFO 写入位置；
  //   3. tail 按“本拍实际有效的释放数”一次性推进。
  // 这样保证 FIFO 内连续填充，不留空洞。
  val freeHits = VecInit((0 until CPUConfig.commitWidth).map { i =>
    io.freeValid(i) && io.freePdst(i) =/= 0.U
  })
  val freeOffsets = Wire(Vec(CPUConfig.commitWidth, UInt(3.W)))
  freeOffsets(0) := 0.U
  for (i <- 1 until CPUConfig.commitWidth) {
    freeOffsets(i) := freeOffsets(i - 1) +& Mux(freeHits(i - 1), 1.U, 0.U)
  }
  val freeCount = PopCount(freeHits)
  for (i <- 0 until CPUConfig.commitWidth) {
    when(freeHits(i)) {
      fifo(idx(tail + freeOffsets(i))) := io.freePdst(i)
    }
  }
  when(freeCount =/= 0.U) {
    tail := tail + freeCount
  }

  // ---- Checkpoint 输出 ----
  // snapHead 使用 snapAllocReq（仅第一个分支及之前 lane 的分配数）
  // snapHead2 使用 snapAllocReq2（仅第二个分支及之前 lane 的分配数）
  // 恢复时，对应 checkpoint 之后 lane 分配的物理寄存器会回到空闲池
  io.snapHead1 := Mux(renameIO.doAlloc, head + renameIO.snapAllocReq1, head)
  io.snapHead2 := Mux(renameIO.doAlloc, head + renameIO.snapAllocReq2, head)
}
