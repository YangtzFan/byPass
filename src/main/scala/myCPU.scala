package mycpu

import chisel3._
import chisel3.util._
import mycpu.dataLane._
import mycpu.device._

// ============================================================================
// myCPU —— 10 级流水线 RISC-V RV32I 处理器核心（乱序四发射过渡版本 + 统一 PRF 方案）
// ============================================================================
// 流水线结构（从前到后）：
//   Fetch(4,BPU) → [FetchBuffer(4in/4out)] → Decode(4) → [DecRenDff]
//   → Rename(4,PRF/RAT) → [RenDisDff] → Dispatch(4,ROB alloc) → [IssueQueue(4in/4out)]
//   → Issue(1,load-use) → [IssRRDff] → ReadReg(1,PRF read) → [RRExDff]
//   → Execute(1,branch verify) → [ExMemDff] → Memory(1,redirect) → [MemRefDff]
//   → Refresh(1,PRF write) → Commit(ROB,RRAT update)
//
// 关键设计点：
//   1. 统一 PRF 方案：128 个物理寄存器（p0~p127），p0 硬编码为 0
//   2. Rename 4-wide：RAT 查询 + FreeList 分配 + rename bypass + checkpoint 保存
//   3. ReadReg：从 PRF 按物理寄存器编号读取操作数
//   4. Refresh：执行结果写入 PRF，ReadyTable 标记 ready
//   5. Commit：更新 RRAT，释放 stalePdst 到 FreeList
//   6. 分支恢复：从 BranchCheckpointTable 恢复 RAT/FreeList/ReadyTable
//   7. 数据旁路使用物理寄存器编号 pdst 进行匹配
// ============================================================================
class myCPU extends Module {
  val io = IO(new Bundle {
    // ---- IROM 指令存储器接口 ----
    val inst_addr_o = Output(UInt(14.W))
    val inst_i = Input(UInt(128.W))

    // ---- DRAM 数据存储器读端口（Memory 阶段 Load 使用）----
    val ram_addr_o  = Output(UInt(32.W))
    val ram_mask_o  = Output(UInt(3.W))
    val ram_rdata_i = Input(UInt(32.W))

    // ---- Commit 阶段观测端口（用于 difftest 对比仿真）----
    val commit_valid     = Output(Bool())
    val commit_pc        = Output(UInt(32.W))
    val commit_reg_wen   = Output(Bool())
    val commit_reg_waddr = Output(UInt(5.W))
    val commit_reg_wdata = Output(UInt(32.W))
    // ---- DRAM 写端口（仅 Commit 阶段 Store 才写入，通过 StoreBuffer）----
    val commit_ram_wen   = Output(Bool())
    val commit_ram_waddr  = Output(UInt(32.W))
    val commit_ram_wdata = Output(UInt(32.W))
    val commit_ram_wmask  = Output(UInt(3.W))
  })

  // ---- 全局控制信号 ----
  val memRedirectValid        = Wire(Bool())     // Memory 阶段重定向使能（分支预测错误）
  val memRedirectAddr         = Wire(UInt(32.W)) // Memory 阶段重定向地址
  val memRedirectRobIdx       = Wire(UInt(CPUConfig.robPtrWidth.W)) // 误预测指令的 ROB 指针
  val memRedirectStoreSeqSnap = Wire(UInt(CPUConfig.storeSeqWidth.W)) // 误预测指令的 storeSeqSnap（用于 SB 精确回滚）
  val fetchPredictJump   = Wire(Bool())       // Fetch 阶段 BPU 预测跳转使能
  val fetchPredictTarget = Wire(UInt(32.W))   // Fetch 阶段 BPU 预测跳转目标

  // =====================================================
  // ============ PC（程序计数器）============
  // =====================================================
  val uPc = Module(new PC)
  uPc.in.mem_redirect_enable  := memRedirectValid
  uPc.in.mem_redirect_addr    := memRedirectAddr
  uPc.in.fetch_predict_enable := fetchPredictJump
  uPc.in.fetch_predict_addr   := fetchPredictTarget

  // =====================================================
  // ============ Fetch（取指 + BPU，4 路宽度）============
  // =====================================================
  val uFetch = Module(new Fetch)
  uFetch.in <> uPc.out
  io.inst_addr_o := uFetch.irom.inst_addr_o
  uFetch.irom.inst_i := io.inst_i
  // ---- BHT 顶层实例化并连接到 Fetch ----
  val uBHT = Option.when(CPUConfig.useBHT)(Module(new BHT(CPUConfig.bhtEntries)))
  if (CPUConfig.useBHT) {
    for (i <- 0 until 4) {
      uBHT.get.io.read_idx(i)   := uFetch.bht.get.read_idx(i)
      uFetch.bht.get.predict(i) := uBHT.get.io.predict(i)
    }
  }
  // Fetch BPU 预测跳转信号（输出到 PC 和 FetchBuffer flush）
  fetchPredictJump   := uFetch.predict.jump
  fetchPredictTarget := uFetch.predict.target

  // =====================================================
  // ============ Fetch → Decode 流水寄存器（FetchBuffer）============
  // =====================================================
  // 注意：Fetch BPU 预测跳转时不冲刷 FetchBuffer 仅在 Memory 重定向时冲刷（清除错误路径指令）
  val uFetchBuffer = Module(new FetchBuffer)
  uFetchBuffer.enq <> uFetch.out
  uFetchBuffer.flush := memRedirectValid

  // =====================================================
  // ============ Decode（4-wide 并行译码）============
  // =====================================================
  val uDecode = Module(new Decode)
  uDecode.in <> uFetchBuffer.deq

  // =====================================================
  // ============ Decode → Rename 流水寄存器（DecRenDff）============
  // =====================================================
  val uDecRenDff = Module(new BaseDff(Vec(4, new DecodedInst), supportFlush = true))
  uDecRenDff.in <> uDecode.out
  uDecRenDff.flush.get := memRedirectValid

  // =====================================================
  // ============ Rename（4-wide 寄存器重命名）============
  // =====================================================
  val uRename = Module(new Rename)
  uRename.in <> uDecRenDff.out
  uRename.flush := memRedirectValid

  // ---- PRF（物理寄存器堆，128 x 32-bit）----
  val uPRF = Module(new PRF)
  // ---- RAT（投机态映射表）----
  val uRAT = Module(new RAT)
  // ---- FreeList（空闲物理寄存器 FIFO）----
  val uFreeList = Module(new FreeList)
  // ---- ReadyTable（物理寄存器就绪状态表）----
  val uReadyTable = Module(new ReadyTable)
  // ---- BranchCheckpointTable（分支 checkpoint 管理）----
  val uBCT = Module(new BranchCheckpointTable)

  // ---- Rename ↔ RAT 连接 ----
  uRename.rat <> uRAT.rwio

  // ---- Rename ↔ FreeList 连接 ----
  uRename.freeList <> uFreeList.renameIO

  // ---- Rename ↔ ReadyTable 连接（置 busy）----
  uReadyTable.io.busyVen  := uRename.readyTable.busyVen
  uReadyTable.io.busyAddr := uRename.readyTable.busyAddr
  // ReadyTable 读端口暂时未使用（将来用于 IssueQueue wakeup），先绑 0
  for (i <- 0 until 8) { uReadyTable.io.raddr(i) := 0.U }

  // ---- Rename ↔ BranchCheckpointTable 连接（保存 checkpoint）----
  uRename.checkpoint.canSave1 := uBCT.io.canSave1
  uRename.checkpoint.canSave2 := uBCT.io.canSave2
  // 第一个 checkpoint 保存
  uBCT.io.saveValid := uRename.checkpoint.saveValid
  uRename.checkpoint.saveIdx := uBCT.io.saveIdx
  // 第二个 checkpoint 保存
  uBCT.io.saveValid2 := uRename.checkpoint.saveValid2
  uRename.checkpoint.saveIdx2 := uBCT.io.saveIdx2

  // ---- 第一个 Checkpoint RAT 快照构建 ----
  // 只叠加 ckptLaneMask 为 true 的 lane 的写入（到第一个被预测分支为止）
  val postRenameRAT = Wire(Vec(CPUConfig.archRegs, UInt(CPUConfig.prfAddrWidth.W)))
  for (r <- 0 until CPUConfig.archRegs) {
    postRenameRAT(r) := uRAT.io.snapData(r)
  }
  for (i <- 0 until 4) {
    when(uRename.checkpoint.ckptLaneMask(i) && uRename.rat.wen(i) && uRename.rat.waddr(i) =/= 0.U) {
      postRenameRAT(uRename.rat.waddr(i)) := uRename.rat.wdata(i)
    }
  }
  uBCT.io.saveRAT   := postRenameRAT
  uBCT.io.saveFLHead := uFreeList.io.snapHead
  uBCT.io.saveFLTail := uFreeList.io.snapTail
  uBCT.io.saveReady  := uReadyTable.io.snapData

  // ---- 第二个 Checkpoint RAT 快照构建 ----
  // 叠加 ckptLaneMask2 为 true 的 lane 的写入（到第二个被预测分支为止）
  val postRenameRAT2 = Wire(Vec(CPUConfig.archRegs, UInt(CPUConfig.prfAddrWidth.W)))
  for (r <- 0 until CPUConfig.archRegs) {
    postRenameRAT2(r) := uRAT.io.snapData(r)
  }
  for (i <- 0 until 4) {
    when(uRename.checkpoint.ckptLaneMask2(i) && uRename.rat.wen(i) && uRename.rat.waddr(i) =/= 0.U) {
      postRenameRAT2(uRename.rat.waddr(i)) := uRename.rat.wdata(i)
    }
  }
  uBCT.io.saveRAT2   := postRenameRAT2
  uBCT.io.saveFLHead2 := uFreeList.io.snapHead2
  uBCT.io.saveFLTail2 := uFreeList.io.snapTail
  uBCT.io.saveReady2  := uReadyTable.io.snapData

  // =====================================================
  // ============ Rename → Dispatch 流水寄存器（RenDisDff）============
  // =====================================================
  // 4-wide 普通流水寄存器，替代原 RenameBuffer
  val uRenDisDff = Module(new BaseDff(new Rename_Dispatch_Payload, supportFlush = true))
  uRenDisDff.in <> uRename.out
  uRenDisDff.flush.get := memRedirectValid

  // =====================================================
  // ============ Dispatch（4-wide，ROB + StoreBuffer 分配）============
  // =====================================================
  val uDisp = Module(new Dispatch)
  uDisp.in <> uRenDisDff.out
  uDisp.flush := memRedirectValid
  // ---- ROB 实例化与 Dispatch 连接 ----
  val uROB = Module(new ROB)
  // ---- StoreBuffer 实例化 ----
  val uStoreBuffer = Module(new StoreBuffer)
  // ---- Dispatch ↔ ROB 分配连接 ----
  uROB.alloc <> uDisp.robAlloc
  // ---- Dispatch ↔ StoreBuffer 分配连接（使用 <> 自动连接）----
  uStoreBuffer.alloc <> uDisp.sbAlloc

  // =====================================================
  // ============ IssueQueue（Vec + FreeList + instSeq 架构）============
  // =====================================================
  val uIssueQueue = Module(new IssueQueue)
  // ---- Dispatch ↔ IssueQueue 分配连接（参照 StoreBuffer 的分配模式）----
  uIssueQueue.alloc <> uDisp.iqAlloc
  // ---- Dispatch → IssueQueue 写入连接 ----
  uIssueQueue.write.valid   := uDisp.out.valid
  uIssueQueue.write.entries := uDisp.out.entries
  uIssueQueue.write.count   := uDisp.out.validCount
  uIssueQueue.flush := memRedirectValid

  // =====================================================
  // ============ Issue（当前 1-wide，Load-Use 冒险检测）============
  // =====================================================
  val uIssue = Module(new Issue)
  // IssueQueue 发射选择接口 ↔ Issue 控制接口
  uIssue.flush := memRedirectValid
  uIssue.iqIssue <> uIssueQueue.issue

  // =====================================================
  // ============ Issue → ReadReg 流水线寄存器（IssRRDff）============
  // =====================================================
  val uIssRRDff = Module(new BaseDff(new Issue_ReadReg_Payload, supportFlush = true))
  uIssRRDff.in <> uIssue.out
  uIssRRDff.flush.get := memRedirectValid
  // ---- Load-Use 冒险检测信号连接 ----
  // Issue 阶段需要知道紧邻的指令 IssRRDff 寄存器中是否是 Load
  // 使用物理目的寄存器 pdst 进行冒险匹配
  uIssue.hazard.pdst        := uIssRRDff.out.bits.pdst
  uIssue.hazard.isValidLoad := uIssRRDff.out.valid && uIssRRDff.out.bits.type_decode_together(4)

  // =====================================================
  // ============ ReadReg（从 PRF 读取物理寄存器值）============
  // =====================================================
  val uReadReg = Module(new ReadReg)
  uReadReg.in <> uIssRRDff.out
  // ---- PRF 读端口连接（按物理寄存器编号读取操作数）----
  uPRF.io.raddr1 := uReadReg.prfRead.raddr1
  uPRF.io.raddr2 := uReadReg.prfRead.raddr2
  uReadReg.prfRead.rdata1 := uPRF.io.rdata1
  uReadReg.prfRead.rdata2 := uPRF.io.rdata2

  // =====================================================
  // ============ ReadReg → Execute 流水线寄存器（RRExDff）============
  // =====================================================
  val uRRExDff = Module(new BaseDff(new ReadReg_Execute_Payload, supportFlush = true))
  uRRExDff.in <> uReadReg.out
  uRRExDff.flush.get := memRedirectValid

  // =====================================================
  // ============ Execute（执行，ALU + 分支验证）============
  // =====================================================
  val uExecute = Module(new Execute)
  uExecute.in <> uRRExDff.out
  // ---- BHT 更新：Execute 阶段得到分支实际结果后回写 BHT ----
  if (CPUConfig.useBHT) {
    // Memory redirect 时抑制更新，防止错误路径的分支污染 BHT
    uBHT.get.io.update_valid := uExecute.bht_update.get.valid && !memRedirectValid
    uBHT.get.io.update_idx   := uExecute.bht_update.get.idx
    uBHT.get.io.update_taken := uExecute.bht_update.get.taken
  }

  // =====================================================
  // ============ Execute → Memory 流水线寄存器（ExMemDff）============
  // =====================================================
  val uExMemDff = Module(new BaseDff(new Execute_Memory_Payload, supportFlush = true))
  uExMemDff.in <> uExecute.out
  uExMemDff.flush.get := memRedirectValid

  // =====================================================
  // ============ Memory（访存 + StoreBuffer 转发 + 分支纠错重定向）============
  // =====================================================
  val uMemory = Module(new Memory)
  uMemory.in <> uExMemDff.out
  io.ram_addr_o := uMemory.io.ram_addr_o
  io.ram_mask_o := uMemory.io.ram_mask_o
  uMemory.io.ram_rdata_i := io.ram_rdata_i

  // ---- StoreBuffer 写入连接（Memory 阶段将 Store 地址和数据写入 StoreBuffer，使用 <> 自动连接）----
  uStoreBuffer.write <> uMemory.sbWrite

  // ---- StoreBuffer 查询连接（Memory 阶段 Load 指令查询 Store-to-Load 转发，使用 <> 自动连接）----
  uStoreBuffer.query <> uMemory.sbQuery

  // ---- Memory 阶段 DRAM 端口冲突信号：drain 正在写时 Load 需要停顿 ----
  // drain.valid 在 effectiveCommitted 旁路下可以在 Store commit 同周期为 true
  // 此时 Load 不能读 DRAM，必须等 drain 完成后重试
  uMemory.drainActive := uStoreBuffer.drain.valid

  // Memory 阶段重定向信号
  memRedirectValid        := uMemory.redirect.valid
  memRedirectAddr         := uMemory.redirect.addr
  memRedirectRobIdx       := uMemory.redirect.robIdx
  memRedirectStoreSeqSnap := uMemory.redirect.storeSeqSnap

  // ROB 回滚：Memory redirect 时将 tail 回滚到误预测指令之后
  uROB.rollback.valid  := memRedirectValid
  uROB.rollback.robIdx := memRedirectRobIdx

  // StoreBuffer 回滚：Memory redirect 时按 storeSeqSnap 精确回滚
  uStoreBuffer.rollback.valid        := memRedirectValid
  uStoreBuffer.rollback.storeSeqSnap := memRedirectStoreSeqSnap

  // =====================================================
  // ============ Memory → Refresh 流水线寄存器（MemRefDff）============
  // =====================================================
  // 注意：MemRefDff 不 flush！Memory 中的误预测指令需要正常流到 Refresh 完成标记
  val uMemRefDff = Module(new BaseDff(new Memory_Refresh_Payload))
  uMemRefDff.in <> uMemory.out

  // =====================================================
  // ============ Refresh（更新 ROB 完成状态）============
  // =====================================================
  val uRefresh = Module(new Refresh)
  uRefresh.in <> uMemRefDff.out
  uROB.refresh <> uRefresh.robRefresh

  // =====================================================
  // ============ Commit（提交，从 ROB 头部取）============
  // =====================================================
  // ---- RRAT（提交态映射表）----
  // Commit 时更新 RRAT[ldst] = pdst，作为架构状态的映射记录
  // RRAT 不需要单独的模块，用简单寄存器实现即可
  val rrat = RegInit(VecInit((0 until CPUConfig.archRegs).map(i => i.U(CPUConfig.prfAddrWidth.W))))

  // Commit 时更新 RRAT 和释放 stalePdst
  val commitRegWen = uROB.commit.regWen && uROB.commit.ldst =/= 0.U
  when(commitRegWen) {
    rrat(uROB.commit.ldst) := uROB.commit.pdst
  }

  // ---- PRF 写端口（Refresh 阶段写回执行结果，而非 Commit 阶段）----
  // PRF 在 Refresh 阶段写入，这里连接 ROB refresh 接口输出
  val refreshValid = uRefresh.robRefresh.valid && uRefresh.robRefresh.regWriteEnable
  uPRF.io.wen   := refreshValid
  uPRF.io.waddr := uRefresh.robRefresh.pdst
  uPRF.io.wdata := uRefresh.robRefresh.regWBData

  // ---- ReadyTable 置 ready（Refresh 阶段标记物理寄存器就绪）----
  uReadyTable.io.readyVen  := refreshValid
  uReadyTable.io.readyAddr := uRefresh.robRefresh.pdst

  // ---- FreeList 释放（Commit 时将 stalePdst 归还）----
  // 条件：指令写寄存器 && ldst != x0（x0 不产生真正的物理寄存器分配）
  uFreeList.io.freeValid := commitRegWen
  uFreeList.io.freePdst  := uROB.commit.stalePdst

  // ---- BranchCheckpointTable 释放（分支提交时释放最老的 checkpoint）----
  // 只有实际保存了 checkpoint 的指令（bType || jalr）在提交时释放 BCT 表项
  // JAL 是无条件跳转，不保存 checkpoint，因此不能释放
  val commitBranch = uROB.commit.valid && uROB.commit.hasCheckpoint
  uBCT.io.freeValid := commitBranch

  // Store 提交标记（通过 StoreBuffer 标记 committed）
  val storeCommitValid = uROB.commit.valid && uROB.commit.isStore && !uROB.commit.mispredict
  uStoreBuffer.commit.valid    := storeCommitValid
  uStoreBuffer.commit.storeSeq := uROB.commit.storeSeq

  // DRAM 写端口：由 StoreBuffer 的 Drain 逻辑驱动
  io.commit_ram_wen   := uStoreBuffer.drain.valid
  io.commit_ram_waddr := uStoreBuffer.drain.addr
  io.commit_ram_wdata := uStoreBuffer.drain.data
  io.commit_ram_wmask := uStoreBuffer.drain.mask

  // Commit 观测信号（供 difftest 使用）
  // difftest 需要逻辑寄存器编号和数据
  io.commit_valid     := uROB.commit.valid
  io.commit_pc        := uROB.commit.pc
  io.commit_reg_wen   := uROB.commit.regWen
  io.commit_reg_waddr := uROB.commit.rd        // difftest 使用逻辑寄存器编号
  io.commit_reg_wdata := uROB.commit.regWBData

  // =====================================================
  // ============ 分支恢复逻辑 ============
  // =====================================================
  // 当 Memory 阶段检测到分支预测失败时：
  // 1. 从 BranchCheckpointTable 恢复 RAT、FreeList、ReadyTable
  // 2. ROB tail 回滚（已由 ROB.rollback 处理）
  // 3. StoreBuffer 回滚（已由 StoreBuffer.rollback 处理）
  // 4. 清除年轻于该分支的流水线项（通过 flush 信号已处理）
  //
  // 注意：当前版本恢复使用 checkpoint 索引 0（简化方案，仅支持单分支 in-flight）
  // TODO: 从 mispredict 指令的 ROB 或管道寄存器中取出 checkpoint 索引
  // 当前过渡方案：从 Memory redirect 中取出分支 checkpoint 索引
  // 每条分支/JALR 指令在 Rename 时保存了 checkpoint 索引，沿流水线传递到 Memory
  val recoverCkptIdx = uMemory.redirect.checkpointIdx // 使用实际的 checkpoint 索引

  // RAT 恢复
  uRAT.io.recover     := memRedirectValid
  uRAT.io.recoverData := uBCT.io.recoverRAT

  // FreeList 恢复
  uFreeList.io.recover     := memRedirectValid
  uFreeList.io.recoverHead := uBCT.io.recoverFLHead
  uFreeList.io.recoverTail := uBCT.io.recoverFLTail

  // ReadyTable 恢复
  uReadyTable.io.recover     := memRedirectValid
  uReadyTable.io.recoverData := uBCT.io.recoverReady

  // BranchCheckpointTable 恢复
  uBCT.io.recoverValid := memRedirectValid
  uBCT.io.recoverIdx   := recoverCkptIdx

  // =====================================================
  // ============ 数据旁路转发连接（Forwarding）============
  // =====================================================
  // 优先级（距离 Execute 越近的越优先）：
  //   1. Memory 级（Execute 前 1 条指令的结果，Load 除外——数据还没读回来）
  //   2. Refresh 级（Execute 前 2 条指令的结果，Load 数据已可用）
  //   3. Commit 级（同周期正在提交的指令结果）
  //   4. Refresh 结果缓存级（最近一次 Refresh 结果的持久副本，覆盖 MemRefDff 清空后的空窗）
  //   兜底：ReadReg 阶段读取的寄存器值（经 RRExDff 传入）

  // 第 1 级旁路：来自 ExMemDff（Memory 级）
  // 使用物理目的寄存器 pdst 进行旁路匹配
  val wen_Memory = uExMemDff.out.valid && uExMemDff.out.bits.regWriteEnable &&
    !uExMemDff.out.bits.type_decode_together(4)  // Load 此时数据不可用
  uExecute.fwd.mem_pdst := uExMemDff.out.bits.pdst
  uExecute.fwd.mem_data := uExMemDff.out.bits.data
  uExecute.fwd.mem_wen  := wen_Memory

  // Execute 级 Load-Use 冒险检测信号（用于检测 ExMemDff 中 Load 导致的 RAW 冒险）
  uExecute.memLoadIsValid := uExMemDff.out.valid
  uExecute.memLoadIsLoad  := uExMemDff.out.bits.type_decode_together(4)

  // 第 2 级旁路：来自 MemRefDff（Refresh 级，Load 数据已可用）
  val refWen = uMemRefDff.out.valid && uMemRefDff.out.bits.regWriteEnable
  uExecute.fwd.ref_pdst := uMemRefDff.out.bits.pdst
  uExecute.fwd.ref_data := uMemRefDff.out.bits.data
  uExecute.fwd.ref_wen  := refWen

  // ---- Refresh 结果缓存（第 4 级旁路，优先级低于 Commit）----
  // 使用物理目的寄存器 pdst 进行匹配
  val refBufValid = RegInit(false.B)
  val refBufPdst  = RegInit(0.U(CPUConfig.prfAddrWidth.W))
  val refBufData  = RegInit(0.U(32.W))
  // 当 MemRefDff 有新的有效写回结果时，更新缓存
  when(refWen) {
    refBufValid := true.B
    refBufPdst  := uMemRefDff.out.bits.pdst
    refBufData  := uMemRefDff.out.bits.data
  }
  uExecute.fwd.refBuf_pdst := refBufPdst
  uExecute.fwd.refBuf_data := refBufData
  uExecute.fwd.refBuf_wen  := refBufValid

  // 第 4 级旁路：Commit 级（同周期 ROB 正在提交的指令结果）
  uExecute.fwd.commit_pdst := uROB.commit.pdst
  uExecute.fwd.commit_data := uROB.commit.regWBData
  uExecute.fwd.commit_wen  := uROB.commit.regWen
}
