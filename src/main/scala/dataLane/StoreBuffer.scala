package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// StoreBuffer（存储缓冲区）—— Vec + FreeList + storeSeq 架构
// ============================================================================
// 核心设计变更（相对于旧版环形缓冲区）：
//   1. 物理槽位用 Vec[sbEntries] 平坦数组存储，不再依赖 head/tail 指针
//   2. FreeStoreBufferList（空闲列表）：位向量管理可分配的物理槽位索引
//   3. storeSeq（逻辑年龄）：全局递增计数器 nextStoreSeq，每分配 N 个 Store 就 +N
//      - 允许自然回绕溢出，使用循环比较（signed-difference MSB）保证正确性
//      - 每个表项记录自己的 storeSeq，用于判断新旧关系
//      - 与物理位置完全解耦，物理槽可以乱序分配和释放
//   4. Load 在 Dispatch 时获取 nextStoreSeq 快照（storeSeqSnap），
//      在 Memory 阶段查询 S2L 转发时，只扫描 storeSeq < storeSeqSnap 的表项
//   5. 精确回滚：清除所有 storeSeq >= rollback.storeSeqSnap 且未 committed 的表项，
//      将对应物理槽归还 FreeList；nextStoreSeq 回退到 rollback.storeSeqSnap
//   6. Commit 与写内存解耦：
//      - ROB 提交 Store 时仅标记表项 committed=true
//      - Drain 逻辑独立运行：每周期选择最老的 committed && addrValid 表项写入内存，
//        写完后释放槽位
//
// 核心职责：
//   1. Dispatch 阶段为 Store 指令分配表项（FreeList 出队 → 设置 valid/storeSeq）
//   2. Memory 阶段写入 Store 地址和数据信息（设置 addrValid=true）
//   3. Memory 阶段 Load 指令查询：
//      a) 在 storeSeq < storeSeqSnap 的所有有效表项中查找
//      b) 若存在地址命中 → 停顿（当前不支持字节/半字提取的转发）
//      c) 若存在地址未知 → 停顿（pending）
//      d) 无匹配 → 正常读内存
//   4. Commit 标记：ROB 提交 Store 时按 storeSeq 找到对应表项标记 committed
//   5. Drain：每周期最多将 1 个已 committed && addrValid 的最老表项写入内存并释放
// ============================================================================
class StoreBuffer(val depth: Int = CPUConfig.sbEntries) extends Module {
  private val idxWidth = CPUConfig.sbIdxWidth    // 物理槽位索引位宽（5 位）
  private val seqWidth = CPUConfig.storeSeqWidth // storeSeq 位宽（8 位）

  // ---- 分配接口（Dispatch 阶段使用）----
  val alloc = IO(Flipped(new SBAllocIO))

  // ---- Store 写入接口（Memory 阶段使用：写入 Store 地址和数据）----
  val write = IO(Input(new SBWriteIO))

  // ---- Load 查询接口（Memory 阶段使用：Store-to-Load 转发）----
  val query = IO(Flipped(new SBQueryIO))

  // ---- 回滚接口（Memory 重定向时使用）----
  val rollback = IO(Flipped(new SBRollbackIO))

  // ---- 提交标记接口（ROB Commit 阶段使用：标记表项为 committed）----
  val commit = IO(Flipped(new SBCommitIO))

  // ---- Drain 接口（独立写内存，与 commit 解耦）----
  val drain = IO(new SBDrainIO)

  // ========================================================================
  // 存储阵列：Vec[depth] 的平坦 StoreBufferEntry 数组
  // 空闲物理槽位列表：FreeStoreBufferList
  // ========================================================================
  val buffer = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(new StoreBufferEntry))))
  val freeVec = VecInit(buffer.map(entry => !entry.valid))
  val freeCount = PopCount(freeVec) // 计算空闲槽位数量（用于 canAlloc 判断）

  // ---- FreeList 分配逻辑 ----
  // 从 freeVec 中按优先级选择最多 4 个空闲槽位
  // 使用级联 PriorityEncoder：每找到一个后遮蔽该位，继续找下一个
  val freeValid = Wire(Vec(4, Bool()))          // 每个分配槽是否有效（备用）
  val freeIdxs = Wire(Vec(4, UInt(idxWidth.W))) // 分配的物理索引
  
  // 第 0 个：直接从 freeVec 中找第一个空闲槽
  val mask0 = freeVec.asUInt
  freeValid(0) := mask0.orR
  freeIdxs(0)  := PriorityEncoder(mask0)

  // 第 1 个：遮蔽前一个已选中的位后继续找
  val mask1 = mask0 & ~(PriorityEncoderOH(mask0))
  freeValid(1) := mask1.orR
  freeIdxs(1)  := PriorityEncoder(mask1)

  // 第 2 个：遮蔽前两个已选中的位
  val mask2 = mask1 & ~(PriorityEncoderOH(mask1))
  freeValid(2) := mask2.orR
  freeIdxs(2)  := PriorityEncoder(mask2)

  // 第 3 个：遮蔽前三个已选中的位
  val mask3 = mask2 & ~(PriorityEncoderOH(mask2))
  freeValid(3) := mask3.orR
  freeIdxs(3)  := PriorityEncoder(mask3)

  // ========================================================================
  // 循环序号比较辅助函数（处理 nextStoreSeq 回绕溢出问题）
  // ========================================================================
  // 原理：将 (a - b) 的无符号结果的最高位视为符号位
  //   - 若 MSB=1，说明 a 在循环意义上"落后于"b（a 更老）
  //   - 若 MSB=0，说明 a "领先于"或"等于" b（a 更新或相等）
  // 正确性前提：任意两个活跃序号的真实距离 < 2^(seqWidth-1)
  //   sbEntries=32，流水线深度~10，故最大距离 < 42 << 128 = 2^(8-1)，安全
  // ========================================================================
  /** a 是否严格比 b 更老（循环比较） */
  def seqOlderThan(a: UInt, b: UInt): Bool = (a - b)(seqWidth - 1)
  /** a 是否比 b 更新或相等（循环比较） */
  def seqNewerOrEq(a: UInt, b: UInt): Bool = !(a - b)(seqWidth - 1)

  // ========================================================================
  // nextStoreSeq：全局递增的 Store 序号计数器（允许自然回绕，使用循环比较保证正确性）
  // ========================================================================
  // 每次 Dispatch 分配 N 个 Store，nextStoreSeq += N（无符号回绕）
  // 所有指令（包括 Load）在 Dispatch 时快照 nextStoreSeq 作为 storeSeqSnap
  val nextStoreSeq = RegInit(0.U(seqWidth.W))

  // ===================== 分配逻辑（Dispatch 阶段）=====================
  alloc.canAlloc := freeCount >= 4.U // canAlloc 不依赖 request，避免与 Dispatch 形成组合环路

  // 返回 FreeList 选中的物理索引和对应的 storeSeq
  for (i <- 0 until 4) {
    alloc.idxs(i)      := freeIdxs(i)
    alloc.storeSeqs(i) := nextStoreSeq + i.U  // 连续的 storeSeq 值
  }
  // 返回当前 nextStoreSeq 快照（所有指令都可以用，Load 用于 storeSeqSnap）
  alloc.nextStoreSeq := nextStoreSeq

  // 判断是否真正进行分配（request > 0 且无回滚）
  val doAlloc = alloc.request > 0.U && !rollback.valid
  when(doAlloc) {
    for (i <- 0 until 4) {
      when(i.U < alloc.request) {
        val physIdx = freeIdxs(i)
        // 初始化表项
        buffer(physIdx).valid     := true.B
        buffer(physIdx).addrValid := false.B // 地址尚未计算（等 Memory 阶段写入）
        buffer(physIdx).committed := false.B // 尚未提交
        buffer(physIdx).addr      := 0.U
        buffer(physIdx).data      := 0.U
        buffer(physIdx).mask      := 0.U
        buffer(physIdx).storeSeq  := nextStoreSeq + i.U  // 分配逻辑年龄
      }
    }
    // 更新全局 storeSeq 计数器
    nextStoreSeq := nextStoreSeq + alloc.request
  }

  // ===================== 写入逻辑（Memory 阶段 Store 指令）=====================
  // Memory 阶段计算出 Store 地址和数据后，写入对应的 StoreBuffer 物理槽位
  when(write.valid) {
    buffer(write.idx).addrValid := true.B
    buffer(write.idx).addr      := write.addr
    buffer(write.idx).data      := write.data
    buffer(write.idx).mask      := write.mask
  }

  // ===================== 查询逻辑（Memory 阶段 Load 指令）=====================
  // 遍历所有表项，查找满足以下条件的 Store：
  //   1. valid=true（已分配）
  //   2. storeSeq 比 storeSeqSnap 更老（即该 Store 在 Load 之前分配）
  //   3. 地址匹配（addrValid=true && addr 相同）
  // 若存在 valid && 更老 && !addrValid → pending=true（地址未知，需等待）
  //
  // 使用循环比较 seqOlderThan 处理 storeSeq 回绕溢出
  val hitVec     = Wire(Vec(depth, Bool())) // 每个表项是否地址命中
  val pendingVec = Wire(Vec(depth, Bool())) // 每个表项是否地址未知（需要等待）
  val seqVec     = Wire(Vec(depth, UInt(seqWidth.W))) // 每个表项的 storeSeq

  for (i <- 0 until depth) {
    val entry = buffer(i)
    // 判断该表项是否是比当前 Load "更老"的有效 Store（循环比较）
    val isOlder = entry.valid && seqOlderThan(entry.storeSeq, query.storeSeqSnap)
    // 地址命中：更老 Store 且地址已知且**字对齐地址**匹配（addr[31:2]）
    // 注意：不能用完整字节地址比较，否则会漏检部分重叠（如 sh addr=18 vs lw addr=16 同属一个字）
    // 使用字对齐地址确保同一 32 位字内的任何 Store/Load 组合都能被检测到
    hitVec(i) := isOlder && entry.addrValid && (entry.addr(31, 2) === query.addr(31, 2))
    // 地址未知：更老 Store 但地址尚未计算完成
    pendingVec(i) := isOlder && !entry.addrValid
    // 记录 storeSeq 用于后续选择最年轻命中
    seqVec(i) := entry.storeSeq
  }

  query.hit     := query.valid && hitVec.asUInt.orR
  query.pending := query.valid && pendingVec.asUInt.orR

  // ---- 选择"最年轻命中"的 Store 数据进行转发 ----
  // 在所有 hitVec(i)=true 的表项中，选 storeSeq 最大（最年轻）的那个
  // 使用循环比较 seqNewerOrEq 处理回绕
  val (youngestSeq, youngestData) = (0 until depth).foldLeft(
    (0.U(seqWidth.W), 0.U(32.W))
  ) { case ((bestSeq, bestData), i) =>
    val better = hitVec(i) && seqNewerOrEq(seqVec(i), bestSeq)
    (Mux(better, seqVec(i), bestSeq), Mux(better, buffer(i).data, bestData))
  }
  query.data := Mux(query.hit, youngestData, 0.U)

  // ===================== 提交标记逻辑（ROB Commit）=====================
  // ROB 提交 Store 时，按 storeSeq 找到对应表项，标记 committed=true
  // 由于 storeSeq 各不相同（唯一），最多命中一个表项
  when(commit.valid) {
    for (i <- 0 until depth) {
      when(buffer(i).valid && buffer(i).storeSeq === commit.storeSeq) {
        buffer(i).committed := true.B
      }
    }
  }

  // ===================== Drain 逻辑（将已提交表项写入内存）=====================
  // 每周期选择一个 committed && addrValid && valid 的最老表项（storeSeq 最小）写入内存
  // 写完后释放槽位（valid=false, freeVec 恢复）
  //
  // 关键：需要组合旁路同周期的 commit 信号，使得 ROB 提交 Store 的同一周期就能 drain
  // 否则 difftest 会观察到 commit 和 DRAM 写入不在同一周期（导致不匹配）
  val effectiveCommitted = Wire(Vec(depth, Bool()))
  for (i <- 0 until depth) {
    // 组合旁路：如果本周期 commit 正好命中这个表项，也算 committed
    val commitHitThisCycle = commit.valid && buffer(i).valid &&
      (buffer(i).storeSeq === commit.storeSeq)
    effectiveCommitted(i) := buffer(i).committed || commitHitThisCycle
  }

  val drainCandidates = Wire(Vec(depth, Bool()))
  for (i <- 0 until depth) {
    drainCandidates(i) := buffer(i).valid && effectiveCommitted(i) && buffer(i).addrValid
  }
  val hasDrain = drainCandidates.asUInt.orR

  // 找 storeSeq 最小（最老）的 drain 候选（使用循环比较 seqOlderThan）
  // 使用 found 标志避免初始 bestSeq 值与实际 storeSeq 冲突
  val (drainIdx, drainSeq, _) = (0 until depth).foldLeft(
    (0.U(idxWidth.W), 0.U(seqWidth.W), false.B)
  ) { case ((bestIdx, bestSeq, found), i) =>
    // 当尚未找到任何候选时，第一个候选直接胜出
    // 当已有候选时，storeSeq 更老的候选胜出（选最老的，循环比较）
    val better = drainCandidates(i) && (!found || seqOlderThan(buffer(i).storeSeq, bestSeq))
    (Mux(better, i.U(idxWidth.W), bestIdx),
     Mux(better, buffer(i).storeSeq, bestSeq),
     found || drainCandidates(i))
  }

  // Drain 输出
  drain.valid := hasDrain
  drain.addr  := buffer(drainIdx).addr
  drain.data  := buffer(drainIdx).data
  drain.mask  := buffer(drainIdx).mask

  // Drain 完成后释放槽位
  when(hasDrain) {
    buffer(drainIdx).valid := false.B
  }

  // ===================== 回滚逻辑（Memory 重定向）=====================
  // 精确回滚：清除所有 storeSeq 比 rollback.storeSeqSnap 更新或相等 且未 committed 的表项
  // 已 committed 的表项不受回滚影响（它们已经被 ROB 确认为正确路径的指令）
  // 同时将 nextStoreSeq 回退到 rollback.storeSeqSnap
  when(rollback.valid) {
    // 回退 nextStoreSeq 到回滚点
    nextStoreSeq := rollback.storeSeqSnap
    // 逐个检查所有表项
    for (i <- 0 until depth) {
      // 只清除：有效 && 未提交 && storeSeq >= 回滚点的表项（循环比较）
      when(buffer(i).valid && !buffer(i).committed &&
           seqNewerOrEq(buffer(i).storeSeq, rollback.storeSeqSnap)) {
        buffer(i).valid := false.B
      }
    }
  }
}
