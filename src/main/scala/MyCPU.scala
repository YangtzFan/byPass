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

  // ---- Rename ↔ ReadyTable 连接（仅置 busy）----
  uReadyTable.io.busyVen  := uRename.readyTable.busyVen
  uReadyTable.io.busyAddr := uRename.readyTable.busyAddr
  // 阶段 1：ReadyTable 读端口改由 IssueQueue 入队当拍使用，避免 Rename→IQ
  // 两级流水间丢失 wakeup。具体连接在 IssueQueue 实例化之后。

  // ---- Rename ↔ BranchCheckpointTable 连接（保存 checkpoint）----
  uBCT.renameRequest <> uRename.ckPointReq

  uBCT.save1.rat     := uRename.ckptRAT.postRename1
  uBCT.save1.flHead  := uFreeList.io.snapHead1
  uBCT.save1.readyTb := uReadyTable.io.snapData

  uBCT.save2.rat     := uRename.ckptRAT.postRename2
  uBCT.save2.flHead  := uFreeList.io.snapHead2
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
  uIssueQueue.flushBranchRobIdx := memRedirectRobIdx
  // 阶段 1：IssueQueue 同拍查询 ReadyTable（8 端口：4 条指令 × 2 源）
  uReadyTable.io.raddr          := uIssueQueue.readyQuery.raddr
  uIssueQueue.readyQuery.rdata  := uReadyTable.io.rdata

  // =====================================================
  // ============ Issue（当前 1-wide，Load-Use 冒险检测）============
  // =====================================================
  val uIssue = Module(new Issue)
  // IssueQueue 发射选择接口 ↔ Issue 控制接口
  uIssue.flush := memRedirectValid
  uIssue.flushBranchRobIdx := memRedirectRobIdx
  uIssue.iqIssue <> uIssueQueue.issue

  // =====================================================
  // ============ Issue → ReadReg 流水线寄存器（IssRRDff）============
  // =====================================================
  val uIssRRDff = Module(new BaseDff(
    new Issue_ReadReg_Payload,
    supportFlush = true,
    getRobIdx = Some((b: Issue_ReadReg_Payload) => b.lanes(0).robIdx)
  ))
  uIssRRDff.in <> uIssue.out
  uIssRRDff.flush.get := memRedirectValid
  uIssRRDff.flushBranchRobIdx.get := memRedirectRobIdx

  // =====================================================
  // ============ ReadReg（从 PRF 读取物理寄存器值）============
  // =====================================================
  val uReadReg = Module(new ReadReg)
  uReadReg.in <> uIssRRDff.out
  // ---- PRF（物理寄存器堆，128 x 32-bit）----
  val uPRF = Module(new PRF)
  // ---- PRF 读端口连接（按物理寄存器编号读取操作数）----
  uPRF.io.raddr := uReadReg.prfRead.raddr
  uReadReg.prfRead.rdata := uPRF.io.rdata

  // =====================================================
  // ============ ReadReg → Execute 流水线寄存器（RRExDff）============
  // =====================================================
  val uRRExDff = Module(new BaseDff(
    new ReadReg_Execute_Payload,
    supportFlush = true,
    getRobIdx = Some((b: ReadReg_Execute_Payload) => b.lanes(0).robIdx)
  ))
  uRRExDff.in <> uReadReg.out
  uRRExDff.flush.get := memRedirectValid
  uRRExDff.flushBranchRobIdx.get := memRedirectRobIdx

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
  val uExMemDff = Module(new BaseDff(
    new Execute_Memory_Payload,
    supportFlush = true,
    getRobIdx = Some((b: Execute_Memory_Payload) => b.lanes(0).robIdx)
  ))
  uExMemDff.in <> uExecute.out
  uExMemDff.flush.get := memRedirectValid
  uExMemDff.flushBranchRobIdx.get := memRedirectRobIdx

  // ---- Load-Use 冒险检测信号连接 ----
  // Issue 阶段需要知道下游所有尚未把 Load 数据写回 PRF 的 Load 位置，
  // 以便在这些 Load 数据对 PRF 可见之前阻止依赖指令进入流水线。
  // 覆盖 3 级：ReadReg(IssRRDff) / Execute(RRExDff) / Memory(ExMemDff)。
  // Refresh 级（MemRefDff）本拍写 PRF，Issue 同拍放行的依赖指令在下一拍才 ReadReg 读 PRF，
  // 已能看到新值，不必加入冒险源。
  uIssue.hazard(0).pdst        := uIssRRDff.out.bits.lanes(0).pdst
  uIssue.hazard(0).isValidLoad := uIssRRDff.out.valid &&
    uIssRRDff.out.bits.lanes(0).type_decode_together(4) && uIssRRDff.out.bits.lanes(0).regWriteEnable
  uIssue.hazard(1).pdst        := uRRExDff.out.bits.lanes(0).pdst
  uIssue.hazard(1).isValidLoad := uRRExDff.out.valid &&
    uRRExDff.out.bits.lanes(0).type_decode_together(4) && uRRExDff.out.bits.lanes(0).regWriteEnable
  uIssue.hazard(2).pdst        := uExMemDff.out.bits.lanes(0).pdst
  uIssue.hazard(2).isValidLoad := uExMemDff.out.valid &&
    uExMemDff.out.bits.lanes(0).type_decode_together(4) && uExMemDff.out.bits.lanes(0).regWriteEnable

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
  // 多 lane Refresh → 多 lane ROB refresh 端口逐 lane 连接
  for (k <- 0 until CPUConfig.refreshWidth) {
    uROB.refresh(k) <> uRefresh.robRefresh(k)
  }

  // =====================================================
  // ============ Commit（提交，从 ROB 头部取）============
  // =====================================================
  // ---- RRAT（提交态映射表）----
  // Commit 时更新 RRAT[ldst] = pdst，作为架构状态的映射记录
  // 多 lane 提交：按 lane 索引顺序更新，同拍同 ldst 后者覆盖前者（乱序下同拍同 ldst 罕见，且程序顺序上后者确实应覆盖前者）
  val rrat = RegInit(VecInit((0 until 32).map(i => i.U(CPUConfig.prfAddrWidth.W))))

  for (i <- 0 until CPUConfig.commitWidth) {
    val cregWen = uROB.commit(i).regWen && uROB.commit(i).ldst =/= 0.U
    when(cregWen) {
      rrat(uROB.commit(i).ldst) := uROB.commit(i).pdst
    }
  }

  // ---- PRF 写端口（Refresh 阶段写回执行结果）----
  // 每 lane 独立写一次 PRF，乱序分配保证同拍不会出现 pdst 冲突
  // refreshValidVec 额外加入"活跃区间"检查：屏蔽选择性 flush 后泄漏到 Refresh 的
  // 年轻 lane（robIdx 已被 rollback 推出 ROB 活跃窗口），避免其污染 PRF / ReadyTable / BCT。
  val refreshValidVec = Wire(Vec(CPUConfig.refreshWidth, Bool()))
  for (k <- 0 until CPUConfig.refreshWidth) {
    refreshValidVec(k) := uRefresh.robRefresh(k).valid && uRefresh.robRefresh(k).regWriteEnable
    uPRF.io.wen(k)   := refreshValidVec(k)
    uPRF.io.waddr(k) := uRefresh.robRefresh(k).pdst
    uPRF.io.wdata(k) := uRefresh.robRefresh(k).regWBData
  }

  // ---- ReadyTable 置 ready（Refresh 阶段标记物理寄存器就绪，多 lane 并行）----
  for (k <- 0 until CPUConfig.refreshWidth) {
    uReadyTable.io.readyVen(k)  := refreshValidVec(k)
    uReadyTable.io.readyAddr(k) := uRefresh.robRefresh(k).pdst
  }

  // ---- Refresh → IssueQueue 的 wakeup 广播（多 lane）----
  for (k <- 0 until CPUConfig.refreshWidth) {
    uIssueQueue.wakeup(k).valid := refreshValidVec(k)
    uIssueQueue.wakeup(k).pdst  := uRefresh.robRefresh(k).pdst
  }

  // ---- FreeList 释放（多 lane Commit）----
  for (i <- 0 until CPUConfig.commitWidth) {
    val cregWen = uROB.commit(i).regWen && uROB.commit(i).ldst =/= 0.U
    uFreeList.io.freeValid(i) := cregWen
    uFreeList.io.freePdst(i)  := uROB.commit(i).stalePdst
  }

  // ---- BranchCheckpointTable 释放（分支提交时释放 1~commitWidth 个最老的 checkpoint）----
  // 多 lane 提交时可能同时提交多个分支，按数量同时推进 BCT head
  val commitBranchVec = VecInit((0 until CPUConfig.commitWidth).map { i =>
    uROB.commit(i).valid && uROB.commit(i).hasCheckpoint
  })
  val commitBranchCount = PopCount(commitBranchVec)
  uBCT.io.freeValid := commitBranchVec.asUInt.orR
  uBCT.io.freeCount := commitBranchCount

  // ---- 告知 BCT 本拍的 Refresh，用于维护每 slot 的 refreshedSinceSnap（多 lane）----
  for (k <- 0 until CPUConfig.refreshWidth) {
    uBCT.io.refreshValid(k) := refreshValidVec(k)
    uBCT.io.refreshAddr(k)  := uRefresh.robRefresh(k).pdst
  }

  // Store 提交路径：
  //   当前 AXI 单事务在飞 + ROB.commitMask 保证本拍至多一个 Store 被允许提交，
  //   且该 Store 必然位于 lane0（ROB 若 lane0 非 Store、lane1 为 Store，commitMask
  //   同样会允许 lane1 提交 —— 但由于 lane1 永不产 Store（IQ 仲裁规则），这条路径
  //   在当前配置下不会发生；为保险起见，这里仍只读取 uROB.commit(0)，后续若允许
  //   lane1 Store 需要把下方连线改为"选首个 Store 的 lane"）
  val headIsStore = uROB.commit(0).isStore
  val headStoreLookupValid = uROB.headReady && headIsStore

  uStoreBuffer.commit.valid := headStoreLookupValid
  uStoreBuffer.commit.storeSeq := uROB.commit(0).storeSeq
  sqEnq.valid := headStoreLookupValid && uStoreBuffer.commit.entryValid
  sqEnq.bits.addr := uStoreBuffer.commit.addr
  sqEnq.bits.data := uStoreBuffer.commit.data
  sqEnq.bits.mask := uStoreBuffer.commit.mask
  sqEnq.bits.wstrb := uStoreBuffer.commit.wstrb
  sqEnq.bits.wdata := uStoreBuffer.commit.wdata
  sqEnq.bits.storeSeq := uROB.commit(0).storeSeq

  val sqEnqFire = sqEnq.fire
  uStoreBuffer.commit.enqSuccess := sqEnqFire

  // P0 语义：head store 只有成功进入 AXIStoreQueue 才算提交，否则持续阻塞 ROB head。
  uROB.commitBlocked := uROB.headReady && headIsStore && !sqEnqFire

  // Commit 观测信号（供 difftest 使用）—— 多 lane 对外输出
  io.commit_count := uROB.commitCount
  for (i <- 0 until CPUConfig.commitWidth) {
    io.commit(i).pc        := uROB.commit(i).pc
    io.commit(i).is_store  := uROB.commit(i).valid && uROB.commit(i).isStore
    io.commit(i).reg_wen   := uROB.commit(i).regWen
    io.commit(i).reg_waddr := uROB.commit(i).rd
    io.commit(i).reg_wdata := uROB.commit(i).regWBData
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

  // ReadyTable 恢复
  uReadyTable.io.recover     := memRedirectValid
  uReadyTable.io.recoverData := uBCT.io.recoverReady

  // BranchCheckpointTable 恢复
  uBCT.io.recoverValid := memRedirectValid
  uBCT.io.recoverIdx   := recoverCkptIdx

  // =====================================================
  // ============ 数据旁路转发连接（Forwarding）============
  // =====================================================
  // 旁路源按 lane Vec 化：
  //   mem 级（ExMemDff）每拍 memoryWidth 个 pdst/data/wen
  //   ref 级（MemRefDff）每拍 refreshWidth 个 pdst/data/wen
  //   commit 级（ROB.commit）每拍 commitWidth 个 pdst/data/wen
  // Execute 内部按 Vec 索引依次 PriorityMux。

  // 第 1 级旁路：ExMemDff（Memory 级）—— Load 数据此拍尚未读回，wen 清 0
  for (k <- 0 until CPUConfig.memoryWidth) {
    val lane = uExMemDff.out.bits.lanes(k)
    val laneValid = uExMemDff.out.valid && uExMemDff.out.bits.validMask(k)
    val isLoad = lane.type_decode_together(4)
    uExecute.fwd.mem_pdst(k) := lane.pdst
    uExecute.fwd.mem_data(k) := lane.data
    uExecute.fwd.mem_wen(k)  := laneValid && lane.regWriteEnable && !isLoad
  }

  // 第 2 级旁路：MemRefDff（Refresh 级，Load 数据已可用）
  for (k <- 0 until CPUConfig.refreshWidth) {
    val lane = uMemRefDff.out.bits.lanes(k)
    val laneValid = uMemRefDff.out.valid && uMemRefDff.out.bits.validMask(k)
    uExecute.fwd.ref_pdst(k) := lane.pdst
    uExecute.fwd.ref_data(k) := lane.data
    uExecute.fwd.ref_wen(k)  := laneValid && lane.regWriteEnable
  }

  // 第 3 级旁路：Commit 级（同拍 ROB 正在提交的指令结果，多 lane）
  for (k <- 0 until CPUConfig.commitWidth) {
    uExecute.fwd.commit_pdst(k) := uROB.commit(k).pdst
    uExecute.fwd.commit_data(k) := uROB.commit(k).regWBData
    uExecute.fwd.commit_wen(k)  := uROB.commit(k).regWen
  }
}
