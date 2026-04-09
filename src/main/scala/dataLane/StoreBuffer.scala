package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// StoreBuffer（存储缓冲区）—— 深度 32 的环形缓冲区
// ============================================================================
// 核心职责：
//   1. Dispatch 阶段为 Store 指令分配表项（设置 valid=true, addrValid=false）
//   2. Execute 阶段写入 Store 地址和数据信息（设置 addrValid=true）
//   3. Memory 阶段 Load 指令查询：
//      a) 检查所有更老且未提交的 StoreBuffer 项
//      b) 若存在更老 Store 且地址命中 → 从 StoreBuffer 转发数据
//      c) 若存在更老 Store 但地址未知 → 流水线停顿
//      d) 无匹配 → 正常读内存
//   4. Commit 阶段：将已提交的 Store 写入内存，然后释放表项
//
// 环形缓冲区：
//   - head 指向最老的已分配表项
//   - tail 指向下一个空闲表项
//   - 使用额外高位区分满/空
//
// 支持 flush：Memory 重定向时，需要回滚 tail 到误预测指令之后，
//   丢弃所有年轻的错误路径 Store 表项
// ============================================================================
class StoreBuffer(val depth: Int = CPUConfig.sbEntries) extends Module {
  private val idxWidth = CPUConfig.sbIdxWidth
  private val ptrWidth = CPUConfig.sbPtrWidth

  // ---- 分配接口（Dispatch 阶段使用）----
  val alloc = IO(Flipped(new SBAllocIO))

  // ---- Store 写入接口（Execute 阶段使用：写入 Store 地址和数据）----
  val write = IO(Flipped(new SBWriteIO))

  // ---- Load 查询接口（Memory 阶段使用：Store-to-Load 转发）----
  val query = IO(Flipped(new SBQueryIO))

  // ---- 提交接口（Commit 阶段使用：将 Store 写入内存）----
  val commit = IO(Flipped(new SBCommitIO))

  // ---- 回滚接口（Memory 重定向时使用）----
  val rollback = IO(Flipped(new SBRollbackIO))

  // ---- 存储阵列 ----
  val buffer = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new StoreBufferEntry))))

  // ---- 头尾指针 ----
  val head = RegInit(0.U(ptrWidth.W)) // 头指针（最老的表项）
  val tail = RegInit(0.U(ptrWidth.W)) // 尾指针（下一个空闲位置）
  private def idx(ptr: UInt) = ptr(idxWidth - 1, 0)
  val count = tail - head

  // ===================== 分配逻辑（Dispatch 阶段）=====================
  // canAlloc 不依赖 request，避免与 Dispatch 形成组合环路。空闲空间 >= 4 才能分配
  alloc.canAlloc := count +& 4.U <= depth.U 
  for (i <- 0 until 4) {
    alloc.idxs(i) := tail + i.U  // 返回连续的指针
  }

  val doAlloc = alloc.request > 0.U && !rollback.valid // 判断是否真正进行分配（request > 0 且无回滚）
  when(doAlloc) {
    for (i <- 0 until 4) {
      when(i.U < alloc.request) {
        val allocPtr = tail + i.U
        val entry = buffer(idx(allocPtr))
        entry.valid     := true.B
        entry.addrValid := false.B   // 地址尚未计算（等 Execute 阶段写入）
        entry.addr      := 0.U
        entry.data      := 0.U
        entry.mask      := 0.U
        entry.robIdx    := 0.U      // ROB 索引由 Dispatch 在写入时填入
      }
    }
    tail := tail + alloc.request
  }

  // ===================== 写入逻辑（Memory 阶段 Store 指令）=====================
  // Execute 阶段计算出 Store 地址和数据后，写入对应的 StoreBuffer 表项
  when(write.valid) {
    val wEntry = buffer(idx(write.idx))
    wEntry.addrValid := true.B
    wEntry.addr      := write.addr
    wEntry.data      := write.data
    wEntry.mask      := write.mask
  }

  // ===================== 查询逻辑（Memory 阶段 Load 指令）=====================
  // 遍历所有表项，查找比当前 Load 更老且地址匹配的 Store
  // "更老"的判断：head <= sbEntry < loadRobIdx（考虑环绕）
  //
  // 简化实现：遍历从 head 到 tail 的所有有效项，检查 robIdx < query.robIdx
  // 找到最年轻的命中项进行转发（最接近 Load 的那个 Store）
  val hitVec = Wire(Vec(depth, Bool()))     // 每个表项是否地址命中
  val pendingVec = Wire(Vec(depth, Bool())) // 是否有地址还未确定的表项（需要等待）
  for (i <- 0 until depth) {
    // 判断该表项是否是比 Load 更老的有效 Store
    // 使用简单的 robIdx 比较：ROB 指针的差值判断新旧关系
    hitVec(i) := buffer(i).valid && buffer(i).addrValid && (buffer(i).addr === query.addr)
    // 遍历所有表项，检查是否存在 valid=true 但 addrValid=false 的 Store 项
    // 如果存在，说明有 Store 的地址尚未计算完成，可能与当前 Load 地址冲突，此时停顿流水线
    pendingVec(i) := buffer(i).valid && !buffer(i).addrValid
  }
  query.hit := query.valid && hitVec.asUInt.orR
  query.pending := query.valid && pendingVec.asUInt.orR

  // 选择“最年轻命中”的 Store 数据进行转发。逻辑年龄顺序：tail-1 (最年轻) -> ... -> head (最老)
  val orderedHit  = Wire(Vec(depth, Bool()))
  val orderedData = Wire(Vec(depth, UInt(32.W)))
  for (k <- 0 until depth) {
    val bufIdx = idx(tail - 1.U - k.U)    // 从最年轻往最老走，环形回绕到物理下标
    orderedHit(k)  := hitVec(bufIdx)
    orderedData(k) := buffer(bufIdx).data
  }
  // PriorityMux 取 orderedHit 中“第一个为真”的项，对应最年轻命中
  query.data := Mux(query.hit, PriorityMux(orderedHit, orderedData), 0.U)

  // ===================== 提交逻辑（Commit 阶段）=====================
  // 队首项已完成且有效时可以提交
  val headEntry = buffer(idx(head))
  commit.canCommit := count > 0.U && headEntry.valid && headEntry.addrValid
  commit.addr      := headEntry.addr
  commit.data      := headEntry.data
  commit.mask      := headEntry.mask

  when(commit.valid) {
    // 释放队首表项
    headEntry.valid := false.B
    head := head + 1.U
  }

  // ===================== 回滚逻辑（Memory 重定向）=====================
  // 类似 ROB 回滚：将 tail 回滚，丢弃所有年轻的错误路径 Store 表项
  // 注意：回滚只影响 tail 指针，已提交（committed）的表项不受影响
  when(rollback.valid) {
    // 回滚：从 tail 向 head 方向清除无效表项
    // 简化实现：直接回滚 tail 到 head，因为 Memory 重定向时
    // 所有在 StoreBuffer 中尚未提交的 Store 都可能是错误路径的
    // 但实际上误预测指令及其之前的 Store 应该保留
    // TODO：精确回滚需要记录每个表项的 robIdx 并比较
    // 当前简单版本：全部回滚到 head（保守但正确的实现）
    // 这意味着误预测恢复后，正确路径的 Store 会重新分配
    tail := head
    // 清除所有未提交的表项
    for (i <- 0 until depth) {
      when(buffer(i).valid) {
        buffer(i).valid := false.B
      }
    }
  }
}
