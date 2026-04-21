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
  // ---- IROM 指令存储器接口 ----
  val inst_addr_o = IO(Output(UInt(14.W)))
  val inst_i = IO(Input(UInt(128.W)))

  // ---- LSU 外部存储器接口（DecoupledIO，替代旧的直连 DRAM 端口）----
  val memReq  = IO(Decoupled(new MemReqBundle)) // 统一 Load/Store 请求
  val memResp = IO(Flipped(Decoupled(new MemRespBundle))) // 统一响应

  val io = IO(new Bundle {
    // ---- Commit 阶段观测端口（用于 difftest 对比仿真）----
    val commit_valid     = Output(Bool())
    val commit_pc        = Output(UInt(32.W))
    val commit_reg_wen   = Output(Bool())
    val commit_reg_waddr = Output(UInt(5.W))
    val commit_reg_wdata = Output(UInt(32.W))
    // ---- DRAM 写端口观测（difftest 用，由 drain 完成事件驱动）----
    val commit_ram_wen   = Output(Bool())
    val commit_ram_waddr = Output(UInt(32.W))
    val commit_ram_wdata = Output(UInt(32.W))
    val commit_ram_wmask = Output(UInt(3.W))
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
  inst_addr_o := uFetch.irom.inst_addr_o
  uFetch.irom.inst_i := inst_i
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
  uBCT.renameRequest <> uRename.ckPointReq

  uBCT.save1.rat     := uRename.ckptRAT.postRename1
  uBCT.save1.flHead  := uFreeList.io.snapHead1
  uBCT.save1.flTail  := uFreeList.io.snapTail
  uBCT.save1.readyTb := uReadyTable.io.snapData

  uBCT.save2.rat     := uRename.ckptRAT.postRename2
  uBCT.save2.flHead  := uFreeList.io.snapHead2
  uBCT.save2.flTail  := uFreeList.io.snapTail
  uBCT.save2.readyTb := uReadyTable.io.snapData

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
  // ---- ROB 和 StoreBuffer 实例化 ----
  val uROB = Module(new ROB)
  val uStoreBuffer = Module(new StoreBuffer)
  // ---- Dispatch ↔ ROB 和 StoreBuffer 分配连接 ----
  uROB.alloc <> uDisp.robAlloc
  uStoreBuffer.alloc <> uDisp.sbAlloc

  // =====================================================
  // ============ IssueQueue（Vec + FreeList + instSeq 架构）============
  // =====================================================
  val uIssueQueue = Module(new IssueQueue)
  // ---- Dispatch ↔ IssueQueue 连接（参照 StoreBuffer 的分配模式）----
  uIssueQueue.alloc <> uDisp.iqAlloc
  uIssueQueue.write := uDisp.out
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
  // ---- PRF（物理寄存器堆，128 x 32-bit）----
  val uPRF = Module(new PRF)
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

  // ---- Store StoreBuffer 写入连接（Memory 阶段将地址、数据、字节掩码写入 StoreBuffer）----
  uStoreBuffer.write := uMemory.sbWrite

  // ---- Load StoreBuffer 查询连接（Memory 阶段字节级转发查询）----
  uStoreBuffer.query <> uMemory.sbQuery

  // Memory 阶段重定向信号
  memRedirectValid        := uMemory.redirect.valid
  memRedirectAddr         := uMemory.redirect.addr
  memRedirectRobIdx       := uMemory.redirect.robIdx
  memRedirectStoreSeqSnap := uMemory.redirect.storeSeqSnap

  // ---- LSU Arbiter（Load/Store 外部访存仲裁）----
  val uLSUArbiter = Module(new LSUArbiter)

  // Memory 阶段 Load 请求/响应 ↔ LSU Arbiter
  uLSUArbiter.io.loadReq  <> uMemory.lsuLoadReq
  uLSUArbiter.io.loadResp <> uMemory.lsuLoadResp

  // LSU Arbiter ↔ 外部接口
  memReq  <> uLSUArbiter.io.memReq
  memResp <> uLSUArbiter.io.memResp

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
  val rrat = RegInit(VecInit((0 until 32).map(i => i.U(CPUConfig.prfAddrWidth.W))))

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
  // 关键：使用 headReady && headIsStore，不受 commitBlocked 门控
  // 避免死锁：commitBlocked 依赖 drain 完成，drain 依赖 committed 标记
  val headIsStore = uROB.commit.isStore  // ROB head 是否是 Store（headEntry 始终可读）
  uStoreBuffer.commit.valid    := uROB.headReady && headIsStore
  uStoreBuffer.commit.storeSeq := uROB.commit.storeSeq

  // =====================================================
  // ============ Store Drain 状态机（将 SB 写入外部存储器）============
  // =====================================================
  // 当 ROB head 是已完成的 Store 且 SB 有可 drain 表项时：
  //   1. 发送 drain 写请求到 LSU Arbiter
  //   2. 等待写响应返回
  //   3. drainComplete → 解除 commitBlocked → ROB 提交 Store
  //
  // 状态机：sDrainIdle → sDrainSent → sDrainIdle（每个 Store 2 周期）
  val sDrainIdle :: sDrainSent :: Nil = Enum(2)
  val drainState = RegInit(sDrainIdle)
  val drainComplete = WireDefault(false.B) // 当前周期 drain 是否完成

  // 保存 drain 信息（用于 difftest 信号输出）
  val savedDrainAddr = RegInit(0.U(32.W))
  val savedDrainData = RegInit(0.U(32.W))
  val savedDrainMask = RegInit(0.U(3.W))

  // Drain 请求（通过 LSU Arbiter）
  val drainReq = uLSUArbiter.io.drainReq
  val drainResp = uLSUArbiter.io.drainResp

  // 默认值
  drainReq.valid := false.B
  drainReq.bits  := 0.U.asTypeOf(new MemReqBundle)
  drainResp.ready := false.B

  switch(drainState) {
    is(sDrainIdle) {
      // 当 ROB head 是 Store 且 SB 有可 drain 表项时发送写请求
      when(uROB.headReady && headIsStore && uStoreBuffer.drain.valid) {
        drainReq.valid        := true.B
        drainReq.bits.isWrite := true.B
        drainReq.bits.addr    := Cat(uStoreBuffer.drain.wordAddr, 0.U(2.W)) // 字对齐地址
        drainReq.bits.wdata   := uStoreBuffer.drain.wdata
        drainReq.bits.wstrb   := uStoreBuffer.drain.wstrb
        when(drainReq.fire) {
          // 保存原始值用于 difftest
          savedDrainAddr := uStoreBuffer.drain.addr
          savedDrainData := uStoreBuffer.drain.data
          savedDrainMask := uStoreBuffer.drain.mask
          drainState := sDrainSent
        }
      }
    }

    is(sDrainSent) {
      // 等待写响应
      drainResp.ready := true.B
      when(drainResp.fire) {
        drainComplete := true.B
        drainState := sDrainIdle
      }
    }
  }

  // StoreBuffer drainAck：写响应返回后释放 SB 槽位
  uStoreBuffer.drain.drainAck := drainComplete

  // ROB commitBlocked：ROB head 是 Store 且 drain 未完成时阻塞提交
  uROB.commitBlocked := uROB.headReady && headIsStore && !drainComplete

  // DRAM 写端口观测（difftest 用）：drain 完成时输出保存的原始值
  io.commit_ram_wen   := drainComplete
  io.commit_ram_waddr := savedDrainAddr
  io.commit_ram_wdata := savedDrainData
  io.commit_ram_wmask := savedDrainMask

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
  //   3. Post-Refresh 级（上一周期 Refresh 写回的结果，覆盖 Load-Use 停顿导致的转发间隙）
  //   4. Commit 级（同周期正在提交的指令结果）
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

  // 第 3 级旁路：Post-Refresh（上一周期或更早 Refresh 写 PRF 的结果）
  // 解决 Load-Use 停顿导致的转发间隙：当 Execute 因 Load-Use 冒险停顿时，
  // MemRefDff 中的数据可能被下一条指令覆盖，而 ReadReg 读取 PRF 的时机早于该 Refresh
  // 写入，导致 ReadReg 兜底值为旧值。此级用寄存器保存最近一次 Refresh 写入的
  // {pdst, data, wen}，在停顿期间持续保持有效，直到 Execute 消耗指令后清除。
  // 这样即使多周期停顿（如外部 Load 阻塞 Memory 导致 Load-Use 冒险持续多周期），
  // 该转发源也不会丢失。
  val postRefWen  = RegInit(false.B)
  val postRefPdst = Reg(UInt(CPUConfig.prfAddrWidth.W))
  val postRefData = Reg(UInt(32.W))
  when(refreshValid) {
    // 新的 Refresh 写回有效：捕获最新数据（优先于清除）
    postRefWen  := true.B
    postRefPdst := uRefresh.robRefresh.pdst
    postRefData := uRefresh.robRefresh.regWBData
  }.elsewhen(uRRExDff.out.fire) {
    // Execute 成功消耗了一条指令（停顿已解除）：清除 Post-Refresh
    // 新进入 Execute 的指令的 ReadReg 阶段在 Refresh 写入之后，PRF 已是最新值
    postRefWen := false.B
  }
  // 停顿期间（refreshValid=false 且 Execute 未消耗）：隐式保持寄存器值不变
  uExecute.fwd.postRef_pdst := postRefPdst
  uExecute.fwd.postRef_data := postRefData
  uExecute.fwd.postRef_wen  := postRefWen

  // 第 4 级旁路：Commit 级（同周期 ROB 正在提交的指令结果）
  uExecute.fwd.commit_pdst := uROB.commit.pdst
  uExecute.fwd.commit_data := uROB.commit.regWBData
  uExecute.fwd.commit_wen  := uROB.commit.regWen
}
