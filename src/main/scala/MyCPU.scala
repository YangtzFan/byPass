package mycpu

import chisel3._
import chisel3.util._
import mycpu.dataLane._
import mycpu.device._
import mycpu.memory._

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
class MyCPU extends Module {
  // ---- IROM 指令存储器接口 ----
  val inst_addr_o = IO(Output(UInt(14.W)))
  val inst_i = IO(Input(UInt(128.W)))

  // ---- AXIStoreQueue 前端接口 ----
  // myCPU 只暴露“commit enqueue / committed-query / load req&resp”前端协议，
  // 由 SoC_Top 在核外实例化 AXIStoreQueue 并统一连接 DRAM。
  val sqEnq = IO(Decoupled(new SQEnqPayload))
  val sqQuery = IO(new AXISQQueryIO)
  val sqLoadAddr = IO(Decoupled(UInt(32.W)))
  val sqLoadData = IO(Flipped(Decoupled(UInt(32.W))))

  val io = IO(new Bundle {
    // ---- Commit 阶段观测端口（用于 difftest 对比仿真）----
    // 采用 Vec + commitCount 形式，支持一拍提交多条指令，
    // 便于后续切换至乱序双发射/四发射时无需改动顶层与验证框架接口。
    // 当前 commitWidth = 1；verilog 端会生成:
    //   io_commit_count              -> 本拍提交的指令数（0..commitWidth）
    //   io_commit_0_pc / _reg_wen / _reg_waddr / _reg_wdata / _is_store
    //   io_commit_1_... (commitWidth>=2 时)
    val commit_count = Output(UInt(log2Ceil(CPUConfig.commitWidth + 1).W))
    val commit = Output(Vec(CPUConfig.commitWidth, new Bundle {
      val pc        = UInt(32.W)
      val is_store  = Bool()
      val reg_wen   = Bool()
      val reg_waddr = UInt(5.W)
      val reg_wdata = UInt(32.W)
    }))
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

  // ---- Load-Use 冒险检测信号连接 ----
  // Issue 阶段需要知道下游所有尚未把 Load 数据写回 PRF 的 Load 位置，
  // 以便在这些 Load 数据对 PRF 可见之前阻止依赖指令进入流水线。
  // 覆盖 3 级：ReadReg(IssRRDff) / Execute(RRExDff) / Memory(ExMemDff)。
  // Refresh 级（MemRefDff）本拍写 PRF，Issue 同拍放行的依赖指令在下一拍才 ReadReg 读 PRF，
  // 已能看到新值，不必加入冒险源。
  uIssue.hazard(0).pdst        := uIssRRDff.out.bits.pdst
  uIssue.hazard(0).isValidLoad := uIssRRDff.out.valid &&
    uIssRRDff.out.bits.type_decode_together(4) && uIssRRDff.out.bits.regWriteEnable
  uIssue.hazard(1).pdst        := uRRExDff.out.bits.pdst
  uIssue.hazard(1).isValidLoad := uRRExDff.out.valid &&
    uRRExDff.out.bits.type_decode_together(4) && uRRExDff.out.bits.regWriteEnable
  uIssue.hazard(2).pdst        := uExMemDff.out.bits.pdst
  uIssue.hazard(2).isValidLoad := uExMemDff.out.valid &&
    uExMemDff.out.bits.type_decode_together(4) && uExMemDff.out.bits.regWriteEnable

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

  // Memory 阶段通过顶层暴露的 AXIStoreQueue 前端接口访问 committed queue / DRAM。
  uMemory.sqQuery <> sqQuery
  uMemory.sqLoadAddr <> sqLoadAddr
  uMemory.sqLoadData <> sqLoadData

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

  // Store 提交路径：
  //   1. ROB head 是 Store 时，先按 storeSeq 向 SB 索引候选；
  //   2. 只有 AXIStoreQueue enqueue 成功，同拍才允许 ROB 真正提交；
  //   3. SB 也只在同一个 enqueue-success 脉冲下释放表项。
  val headIsStore = uROB.commit.isStore
  val headStoreLookupValid = uROB.headReady && headIsStore

  uStoreBuffer.commit.valid := headStoreLookupValid
  uStoreBuffer.commit.storeSeq := uROB.commit.storeSeq
  sqEnq.valid := headStoreLookupValid && uStoreBuffer.commit.entryValid
  sqEnq.bits.addr := uStoreBuffer.commit.addr
  sqEnq.bits.data := uStoreBuffer.commit.data
  sqEnq.bits.mask := uStoreBuffer.commit.mask
  sqEnq.bits.wstrb := uStoreBuffer.commit.wstrb
  sqEnq.bits.wdata := uStoreBuffer.commit.wdata
  sqEnq.bits.storeSeq := uROB.commit.storeSeq

  val sqEnqFire = sqEnq.fire
  uStoreBuffer.commit.enqSuccess := sqEnqFire

  // P0 语义：head store 只有成功进入 AXIStoreQueue 才算提交，否则持续阻塞 ROB head。
  uROB.commitBlocked := uROB.headReady && headIsStore && !sqEnqFire

  // Commit 观测信号（供 difftest 使用）
  // 现在 store 的 difftest 比对点已经前移到 AXIStoreQueue enqueue 成功同拍，
  // 因此顶层可以直接使用这里的 commit 输出，不再额外做重排。
  // Commit 观测信号（供 difftest 使用）
  // 当前 ROB 仍为单条提交（commitWidth=1），因此只有 lane 0 真正输出信号。
  // 后续若扩展 ROB 多条提交，只需新增 uROB.commitVec 并逐 lane 连接即可。
  io.commit_count := Mux(uROB.commit.valid, 1.U, 0.U)
  io.commit(0).pc        := uROB.commit.pc
  io.commit(0).is_store  := uROB.commit.valid && uROB.commit.isStore
  io.commit(0).reg_wen   := uROB.commit.regWen
  io.commit(0).reg_waddr := uROB.commit.rd        // difftest 使用逻辑寄存器编号
  io.commit(0).reg_wdata := uROB.commit.regWBData
  // 如果 commitWidth > 1，多出的高位 lane 默认为无效。
  for (i <- 1 until CPUConfig.commitWidth) {
    io.commit(i).pc        := 0.U
    io.commit(i).is_store  := false.B
    io.commit(i).reg_wen   := false.B
    io.commit(i).reg_waddr := 0.U
    io.commit(i).reg_wdata := 0.U
  }

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
  //   兜底：ReadReg 阶段读取的寄存器值（经 RRExDff 传入）

  // 第 1 级旁路：来自 ExMemDff（Memory 级）
  // 使用物理目的寄存器 pdst 进行旁路匹配
  val wen_Memory = uExMemDff.out.valid && uExMemDff.out.bits.regWriteEnable &&
    !uExMemDff.out.bits.type_decode_together(4)  // Load 此时数据不可用
  uExecute.fwd.mem_pdst := uExMemDff.out.bits.pdst
  uExecute.fwd.mem_data := uExMemDff.out.bits.data
  uExecute.fwd.mem_wen  := wen_Memory

  // 第 2 级旁路：来自 MemRefDff（Refresh 级，Load 数据已可用）
  val refWen = uMemRefDff.out.valid && uMemRefDff.out.bits.regWriteEnable
  uExecute.fwd.ref_pdst := uMemRefDff.out.bits.pdst
  uExecute.fwd.ref_data := uMemRefDff.out.bits.data
  uExecute.fwd.ref_wen  := refWen

  // 第 3 级旁路：Commit 级（同周期 ROB 正在提交的指令结果）
  uExecute.fwd.commit_pdst := uROB.commit.pdst
  uExecute.fwd.commit_data := uROB.commit.regWBData
  uExecute.fwd.commit_wen  := uROB.commit.regWen
}
