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
  // ---- 取指存储访问接口（替代旧的 IROM 直连）----
  // SoC_Top 根据 icacheSize 选择两种实现：
  //   1) icacheSize=0：reqQ/respQ 直接接到 IROM（旁路）
  //   2) icacheSize>0：reqQ/respQ 接到 I-Cache 前端，I-Cache 后端再走另一对 Queue 到 IROM
  // 两种实现均符合 Decoupled 协议，CPU 内部统一抽象。
  val fetchMem = IO(new Bundle {
    val reqAddr  = Decoupled(UInt(14.W))
    val respData = Flipped(Decoupled(UInt(128.W)))
  })

  // ---- AXIStoreQueue 前端接口 ----
  // myCPU 只暴露“commit enqueue / committed-query / load req&resp”前端协议，
  // 由 SoC_Top 在核外实例化 AXIStoreQueue 并统一连接 DRAM。
  // Phase A.2：sqEnq 升级为 Vec(K) 多端口，K = storeLanes.size = 2。
  val sqEnq = IO(Vec(LaneCapability.storeLanes.size, Decoupled(new SQEnqPayload)))
  // TD-B：sqQuery 升级为 Vec(N) 多查询口（Memory 阶段每个 Load lane 独立查询）。
  val sqQuery = IO(Vec(LaneCapability.loadLanes.size, new AXISQQueryIO))
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
      // Phase A.2：该 lane 的 store 序号（0..K-1）；当 is_store=false 时为 0。
      // SoC_Top 用该值从 axiMasterDebug Vec(K) 中选出对应 enq lane 的 ram_* 观测。
      val store_ordinal = UInt(log2Ceil(LaneCapability.storeLanes.size + 1).W)
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
  fetchMem.reqAddr <> uFetch.mem.reqAddr
  uFetch.mem.respData <> fetchMem.respData
  uFetch.flush := memRedirectValid // Memory 重定向时丢弃在飞取指请求，避免错误数据进入 FetchBuffer
  // ---- BHT 顶层实例化并连接到 Fetch ----
  val uBHT = Option.when(CPUConfig.useBHT)(Module(new BHT(CPUConfig.bhtEntries)))
  if (CPUConfig.useBHT) {
    for (i <- 0 until 4) {
      uBHT.get.io.read_idx(i)   := uFetch.bht.get.read_idx(i)
      uFetch.bht.get.predict(i) := uBHT.get.io.predict(i)
    }
  }
  // ---- BTB 顶层实例化并连接到 Fetch（读）----
  val uBTB = Option.when(CPUConfig.useBTB)(Module(new BTB(CPUConfig.btbEntries)))
  if (CPUConfig.useBTB) {
    for (i <- 0 until 4) {
      uBTB.get.io.read_pc(i)   := uFetch.btb.get.read_pc(i)
      uFetch.btb.get.hit(i)    := uBTB.get.io.hit(i)
      uFetch.btb.get.target(i) := uBTB.get.io.target(i)
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
  // ReadyTable 读端口改由 IssueQueue 入队当拍使用，避免 Rename→IQ
  // 两级流水间丢失 wakeup。具体连接在 IssueQueue 实例化之后。

  // ---- Rename ↔ BranchCheckpointTable 连接（保存 checkpoint）----
  uBCT.renameRequest <> uRename.ckPointReq

  // Phase A.3：BCT save 接口已 Vec 化（saveDataIn(k)），ckptSaveWidth=2 时与
  // 原 save1/save2 等价；后续 A.4 提到 4 时此处仅需补两组连线。
  uBCT.saveDataIn(0).rat     := uRename.ckptRAT.postRename1
  uBCT.saveDataIn(0).flHead  := uFreeList.io.snapHead1
  uBCT.saveDataIn(0).readyTb := uRename.ckptReadyTb.postRename1

  uBCT.saveDataIn(1).rat     := uRename.ckptRAT.postRename2
  uBCT.saveDataIn(1).flHead  := uFreeList.io.snapHead2
  uBCT.saveDataIn(1).readyTb := uRename.ckptReadyTb.postRename2

  // 提供当前 ReadyTable 全量给 Rename，用于叠加同拍 busyVen 形成正确分支快照
  uRename.readyTbSnapIn := uReadyTable.io.snapData

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
  // IssueQueue 同拍查询 ReadyTable（8 端口：4 条指令 × 2 源）
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
  // TD-C：post-IQ DFF 改用 per-lane 选择性 flush。
  // 背景：IQ 是 OoO 调度，bundle 内 lane0/lane1 的 robIdx 互不相同；旧的"按 lane0
  // robIdx 整 bundle 决策"在以下场景会漏 flush：
  //   bundle = [lane0=老于 mispredict, lane1=年轻于 mispredict]，
  //   lane0 比 mispredict 老 → BaseDff 误判为整 bundle 留下，lane1 跑了错误路径。
  // 解决：逐 lane 比较 robIdx，把"年轻于分支"的 lane 在 validMask 中清零；
  //       全部 lane 都被清零再丢弃整 bundle。
  private def laneFlushIssRR(b: Issue_ReadReg_Payload, branchIdx: UInt): (Issue_ReadReg_Payload, Bool) = {
    val w = CPUConfig.robPtrWidth
    val n = CPUConfig.issueWidth
    val newBits = Wire(Vec(n, Bool()))
    for (k <- 0 until n) {
      val rob = b.lanes(k).robIdx
      val younger = ((rob - branchIdx)(w - 1) === 0.U) && (rob =/= branchIdx)
      newBits(k) := b.validMask(k) && !younger
    }
    val masked = WireInit(b)
    masked.validMask := newBits.asUInt
    (masked, newBits.asUInt.orR)
  }
  private def laneFlushRREx(b: ReadReg_Execute_Payload, branchIdx: UInt): (ReadReg_Execute_Payload, Bool) = {
    val w = CPUConfig.robPtrWidth
    val n = CPUConfig.issueWidth
    val newBits = Wire(Vec(n, Bool()))
    for (k <- 0 until n) {
      val rob = b.lanes(k).robIdx
      val younger = ((rob - branchIdx)(w - 1) === 0.U) && (rob =/= branchIdx)
      newBits(k) := b.validMask(k) && !younger
    }
    val masked = WireInit(b)
    masked.validMask := newBits.asUInt
    (masked, newBits.asUInt.orR)
  }
  private def laneFlushExMem(b: Execute_Memory_Payload, branchIdx: UInt): (Execute_Memory_Payload, Bool) = {
    val w = CPUConfig.robPtrWidth
    val n = CPUConfig.memoryWidth
    val newBits = Wire(Vec(n, Bool()))
    for (k <- 0 until n) {
      val rob = b.lanes(k).robIdx
      val younger = ((rob - branchIdx)(w - 1) === 0.U) && (rob =/= branchIdx)
      newBits(k) := b.validMask(k) && !younger
    }
    val masked = WireInit(b)
    masked.validMask := newBits.asUInt
    (masked, newBits.asUInt.orR)
  }

  val uIssRRDff = Module(new BaseDff(
    new Issue_ReadReg_Payload,
    supportFlush = true,
    laneFlush = Some(laneFlushIssRR _)
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
    laneFlush = Some(laneFlushRREx _)
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
  // ---- BTB 更新：Execute 解析 JALR 得到真实目标后写回 BTB ----
  if (CPUConfig.useBTB) {
    // 同理，Memory redirect 时抑制写入，避免错误路径污染 BTB
    uBTB.get.io.update_valid  := uExecute.btb_update.get.valid && !memRedirectValid
    uBTB.get.io.update_pc     := uExecute.btb_update.get.pc
    uBTB.get.io.update_target := uExecute.btb_update.get.target
  }

  // =====================================================
  // ============ Execute → Memory 流水线寄存器（ExMemDff）============
  // =====================================================
  val uExMemDff = Module(new BaseDff(
    new Execute_Memory_Payload,
    supportFlush = true,
    laneFlush = Some(laneFlushExMem _)
  ))
  uExMemDff.in <> uExecute.out
  uExMemDff.flush.get := memRedirectValid
  uExMemDff.flushBranchRobIdx.get := memRedirectRobIdx

  // ---- Load-Use 冒险检测信号连接 ----
  // TD-B：loadLanes 升到 2，下游 RR/Execute/Memory 三级里**每条 Load lane**都可能持有
  // 未写回 PRF 的 Load。hazard 槽位扩到 3 * loadLanes.size = 6（再 + MSHR 2 槽 = 8）。
  // 槽位顺序：(level 0..2) × (loadLane 0..N-1)；与 Issue.scala::NumLoadHazardSrcs 对应。
  for ((laneIdx, qIdx) <- LaneCapability.loadLanes.zipWithIndex) {
    // RR 级（uIssRRDff.out）
    val rrSlot = 0 * LaneCapability.loadLanes.size + qIdx
    uIssue.hazard(rrSlot).pdst        := uIssRRDff.out.bits.lanes(laneIdx).pdst
    uIssue.hazard(rrSlot).isValidLoad := uIssRRDff.out.valid &&
      uIssRRDff.out.bits.validMask(laneIdx) &&
      uIssRRDff.out.bits.lanes(laneIdx).type_decode_together(4) &&
      uIssRRDff.out.bits.lanes(laneIdx).regWriteEnable
    // Execute 级（uRRExDff.out）
    val exSlot = 1 * LaneCapability.loadLanes.size + qIdx
    uIssue.hazard(exSlot).pdst        := uRRExDff.out.bits.lanes(laneIdx).pdst
    uIssue.hazard(exSlot).isValidLoad := uRRExDff.out.valid &&
      uRRExDff.out.bits.validMask(laneIdx) &&
      uRRExDff.out.bits.lanes(laneIdx).type_decode_together(4) &&
      uRRExDff.out.bits.lanes(laneIdx).regWriteEnable
    // Memory 级（uExMemDff.out）
    val memSlot = 2 * LaneCapability.loadLanes.size + qIdx
    uIssue.hazard(memSlot).pdst        := uExMemDff.out.bits.lanes(laneIdx).pdst
    uIssue.hazard(memSlot).isValidLoad := uExMemDff.out.valid &&
      uExMemDff.out.bits.validMask(laneIdx) &&
      uExMemDff.out.bits.lanes(laneIdx).type_decode_together(4) &&
      uExMemDff.out.bits.lanes(laneIdx).regWriteEnable
  }
  // η2：MSHR Vec(2) 的两个槽位填到 hazard 末尾（slot 6/7），真正连线在 Memory 实例化之后。

  // =====================================================
  // ============ Memory（访存 + StoreBuffer 转发 + 分支纠错重定向）============
  // =====================================================
  val uMemory = Module(new Memory)
  uMemory.in <> uExMemDff.out

  // ---- Store StoreBuffer 写入连接（Memory 阶段将地址、数据、字节掩码写入 StoreBuffer）----
  // 阶段 D'：sbWrite 升级为 Vec（lane0 + lane1），按端口逐位连接。
  for (p <- 0 until LaneCapability.storeLanes.size) {
    uStoreBuffer.write(p) := uMemory.sbWrite(p)
  }

  // ---- Execute 阶段早期地址写入（解决 anyOlderUnk 死锁）----
  // 将 Execute.out（= uExMemDff.in）快照传给 Memory，由 Memory 生成 sbWriteEarly 信号，
  // 再连到 StoreBuffer 的 earlyAddrWrite 端口，提前把 store 的 addrValid 设为 true。
  uMemory.inExecOut.valid := uExecute.out.valid
  uMemory.inExecOut.bits  := uExecute.out.bits
  for (p <- 0 until LaneCapability.storeLanes.size) {
    uStoreBuffer.earlyAddrWrite(p) := uMemory.sbWriteEarly(p)
  }

  // ---- Load StoreBuffer 查询连接（Memory 阶段字节级转发查询）----
  uStoreBuffer.query <> uMemory.sbQuery

  // Memory 阶段重定向信号
  // v15 修复（algo_array_ops_ooo4 死锁）：
  // 用 BCT.recoverIdxInRange 校验 redirect.checkpointIdx 是否落在 BCT 在飞区间。
  // 越界视为 stale memRedirect（详见 BranchCheckpointTable.scala 中说明），
  // 整体抑制 memRedirect 的所有副作用（pipeline flush / ROB rollback / RAT/FreeList 恢复
  // / BCT 状态变更等），避免幻影 checkpoint 占满 BCT 物理槽导致永久死锁。
  memRedirectValid        := uMemory.redirect.valid && uBCT.io.recoverIdxInRange
  memRedirectAddr         := uMemory.redirect.addr
  memRedirectRobIdx       := uMemory.redirect.robIdx
  memRedirectStoreSeqSnap := uMemory.redirect.storeSeqSnap

  // Memory 阶段通过顶层暴露的 AXIStoreQueue 前端接口访问 committed queue / DRAM。
  uMemory.sqQuery <> sqQuery
  uMemory.sqLoadAddr <> sqLoadAddr
  uMemory.sqLoadData <> sqLoadData

  // η2：MSHR flush 输入——分支误预测时若 MSHR 中 Load 比误预测分支更年轻则清掉。
  uMemory.flushIn.valid        := memRedirectValid
  uMemory.flushIn.branchRobIdx := memRedirectRobIdx

  // η2/TD-B：hazard 末尾 2 个槽位——Memory 阶段 MSHR 两个槽中持有的 outstanding Load。
  // 槽号 = 3 * loadLanes.size 起，共 2 个（MSHR 两个 entry）。
  private val mshrHazardBase = 3 * LaneCapability.loadLanes.size
  uIssue.hazard(mshrHazardBase + 0).pdst        := uMemory.mshrPending(0).pdst
  uIssue.hazard(mshrHazardBase + 0).isValidLoad := uMemory.mshrPending(0).valid && uMemory.mshrPending(0).regWriteEnable
  uIssue.hazard(mshrHazardBase + 1).pdst        := uMemory.mshrPending(1).pdst
  uIssue.hazard(mshrHazardBase + 1).isValidLoad := uMemory.mshrPending(1).valid && uMemory.mshrPending(1).regWriteEnable

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
  // η2 / TD-D：MSHR 写口仲裁（mshrComplete: Vec(2)）。
  //   - uRefresh.robRefresh(k) 是"正常 Refresh 路径"的写源；
  //   - uMemory.mshrComplete(j) 是"MSHR 完成路径"的写源（j ∈ 0..1）；
  //   - 两类写源抢同一个 ROB.refresh / PRF.write / ReadyTable / IQ.wakeup / BCT 写端口。
  // 仲裁策略（TD-D 升级为 2 路并行）：依次为 j=0,1 找一条"既未被 Refresh 占用、也未被
  // 前序 j' < j 的 mshrComplete 占用"的最低 lane k 接管。
  // mshrLaneClaimByJ(j)(k)=1 表示 mshrComplete(j) 通过 lane k 完成本拍写回。
  val numMshrComp = uMemory.mshrComplete.length // = 2
  val mshrLaneClaimByJ = Wire(Vec(numMshrComp, Vec(CPUConfig.refreshWidth, Bool())))
  for (j <- 0 until numMshrComp) {
    var claimedJ: Bool = false.B
    for (k <- 0 until CPUConfig.refreshWidth) {
      val freeFromRefresh = !uRefresh.robRefresh(k).valid
      // 该 lane 是否已被 j' < j 的 mshrComplete 占走（静态展开 OR）
      val takenByEarlier: Bool =
        (0 until j).map(jp => mshrLaneClaimByJ(jp)(k)).foldLeft(false.B)(_ || _)
      val pick = uMemory.mshrComplete(j).valid && freeFromRefresh && !takenByEarlier && !claimedJ
      mshrLaneClaimByJ(j)(k) := pick
      claimedJ = claimedJ || pick
    }
    uMemory.mshrComplete(j).ack := mshrLaneClaimByJ(j).asUInt.orR
  }
  // 每条 refresh lane 是否被任一 mshrComplete 接管（用于 effRef* 选择）。
  val mshrFreeLaneOH = Wire(Vec(CPUConfig.refreshWidth, Bool()))
  for (k <- 0 until CPUConfig.refreshWidth) {
    mshrFreeLaneOH(k) := (0 until numMshrComp).map(j => mshrLaneClaimByJ(j)(k)).reduce(_ || _)
  }

  // 每 lane 计算"有效 refresh 源"（MSHR 接管 vs 正常 Refresh）。
  val effRefValid = Wire(Vec(CPUConfig.refreshWidth, Bool()))
  val effRefIdx   = Wire(Vec(CPUConfig.refreshWidth, UInt(CPUConfig.robPtrWidth.W)))
  val effRefPdst  = Wire(Vec(CPUConfig.refreshWidth, UInt(CPUConfig.prfAddrWidth.W)))
  val effRefData  = Wire(Vec(CPUConfig.refreshWidth, UInt(32.W)))
  val effRefWen   = Wire(Vec(CPUConfig.refreshWidth, Bool()))
  for (k <- 0 until CPUConfig.refreshWidth) {
    val rr  = uRefresh.robRefresh(k)
    val sel = mshrFreeLaneOH(k)
    // lane k 被哪一路 mshrComplete 占走？同拍至多一路占同一 lane（逐字段 PriorityMux）
    val claimBitsK = (0 until numMshrComp).map(j => mshrLaneClaimByJ(j)(k))
    def pickField[T <: chisel3.Data](fn: Int => T, default: T): T =
      MuxCase(default, (0 until numMshrComp).map { j => claimBitsK(j) -> fn(j) })
    val mshrRobIdx = pickField(j => uMemory.mshrComplete(j).robIdx, 0.U(CPUConfig.robPtrWidth.W))
    val mshrPdstK  = pickField(j => uMemory.mshrComplete(j).pdst,   0.U(CPUConfig.prfAddrWidth.W))
    val mshrDataK  = pickField(j => uMemory.mshrComplete(j).data,   0.U(32.W))
    val mshrWenK   = pickField(j => uMemory.mshrComplete(j).regWriteEnable, false.B)
    effRefValid(k) := Mux(sel, true.B, rr.valid)
    effRefIdx(k)   := Mux(sel, mshrRobIdx, rr.idx)
    effRefPdst(k)  := Mux(sel, mshrPdstK,  rr.pdst)
    effRefData(k)  := Mux(sel, mshrDataK,  rr.regWBData)
    effRefWen(k)   := Mux(sel, mshrWenK,   rr.regWriteEnable)
  }

  // 多 lane ROB refresh 端口逐 lane 连接（用 effRef* 统一驱动）
  for (k <- 0 until CPUConfig.refreshWidth) {
    uROB.refresh(k).valid          := effRefValid(k)
    uROB.refresh(k).idx            := effRefIdx(k)
    uROB.refresh(k).regWBData      := effRefData(k)
    uROB.refresh(k).pdst           := effRefPdst(k)
    uROB.refresh(k).regWriteEnable := effRefWen(k)
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

  // ---- PRF 写端口（Refresh 阶段写回执行结果，含 η2 MSHR 仲裁后的写回）----
  // refreshValidVec 现在统一用 effRef*（MSHR 接管时由 effRef 注入 MSHR.complete 数据）。
  // 活跃区间保护：仅当 robIdx 落在 [head, tail)（即 (idx - head) < count）时才允许
  // 写 PRF / ReadyTable / 广播 IQ wakeup。否则来自被 rollback 释放的旧 MSHR 或乱序
  // 完成的旧执行结果会以 stale 数据污染 PRF，并通过 wakeup 让依赖指令提前发射读取
  // 脏数据（参见 sh 地址错误 bug：cycle 3131 MSHR 以 robIdx=0xf7 写回 PRF[x75]=0x20000，
  // 此时 ROB 已空 head=tail=0xf5，sh 于 cycle 3133 读得错误地址基址）。
  val refreshValidVec = Wire(Vec(CPUConfig.refreshWidth, Bool()))
  for (k <- 0 until CPUConfig.refreshWidth) {
    val inRange = (effRefIdx(k) - uROB.headPtr) < uROB.countOut
    refreshValidVec(k) := effRefValid(k) && effRefWen(k) && inRange
    uPRF.io.wen(k)   := refreshValidVec(k)
    uPRF.io.waddr(k) := effRefPdst(k)
    uPRF.io.wdata(k) := effRefData(k)
  }

  // ---- ReadyTable 置 ready（Refresh 阶段标记物理寄存器就绪，多 lane 并行）----
  for (k <- 0 until CPUConfig.refreshWidth) {
    uReadyTable.io.readyVen(k)  := refreshValidVec(k)
    uReadyTable.io.readyAddr(k) := effRefPdst(k)
  }

  // ---- Refresh → IssueQueue 的 wakeup 广播（多 lane）----
  for (k <- 0 until CPUConfig.refreshWidth) {
    uIssueQueue.wakeup(k).valid := refreshValidVec(k)
    uIssueQueue.wakeup(k).pdst  := effRefPdst(k)
  }
  for (k <- 0 until CPUConfig.memoryWidth) {
    val memLane  = uExMemDff.out.bits.lanes(k)
    val memAdvance = uExMemDff.out.fire
    val memValid = memAdvance && uExMemDff.out.bits.validMask(k)
    // η2-step(b) / TD-B：若本拍 Load 进入 MSHR（mshrCaptureFire(qIdx)=1），则该 Load
    // 所在 lane 的 validMask 已经被 Memory 置 0，γ-wake 由 Refresh 路径接管时机变成
    // "MSHR 完成那拍"。这里需要**逐 lane**抑制 γ-wake：只有当前 lane 在 loadLanes 中
    // 且对应 mshrCaptureFire 位为 1 时才禁止广播；非 Load lane（k 不在 loadLanes 内）不抑制。
    val laneSuppress: Bool =
      if (LaneCapability.loadLanes.contains(k)) {
        val qIdx = LaneCapability.loadLanes.indexOf(k)
        uMemory.mshrCaptureFire(qIdx)
      } else {
        false.B
      }
    uIssueQueue.wakeup(CPUConfig.refreshWidth + k).valid :=
      memValid && memLane.regWriteEnable && (memLane.pdst =/= 0.U) && !laneSuppress
    uIssueQueue.wakeup(CPUConfig.refreshWidth + k).pdst := memLane.pdst
  }
  // 阶段 β：Execute 级 wakeup（提前 1 拍广播）。
  // [v19 TD-E 严格门控 v4] 三重门控：
  //   1) uRRExDff.out.fire   —— 本拍生产者真的从 RR/Ex 推进到 ExMemDff（v18 已有）；
  //   2) uMemory.in.ready    —— 当前 ExMemDff→Memory 通路无反压（v3 增）；
  //   3) !anyLoadInExBundle —— 当前 RR/Ex bundle 内任一 lane 不是 Load。
  //
  // 不变量论证（重点在第 3 条，针对 dual-Load 模式 Bug A）：
  //   生产者在 cycle N 进入 ExMemDff（end of N capture），cycle N+1 由 ExMemDff.out 喂入 Memory。
  //   Memory.memStall 由"本 bundle 是否含需走 MSHR 的 Load"决定 —— Load lane 与 ALU lane
  //   是同 bundle 流水推进，若 lane1=Load 因 MSHR 容量/older-unknown 触发 memStall，
  //   则同 bundle 的 lane0=ALU 生产者也被一并卡在 ExMemDff 多拍，PRF 写时点后移
  //   → 消费者按 N+3 拍读 PRF 命中 stale；
  //   故"本 Ex bundle 内不含 Load"才能保证 ExMemDff→Memory→MemRefDff→Refresh 直通。
  //   - 配合 Memory.in.ready=1，再加"我也不是 Load 同 bundle"，就保证 N+1 拍 Memory 不会
  //     因当前 bundle 自身停滞，进而保证 PRF 在 N+3 拍可见。
  //   - 唯一遗漏路径：N+1 拍 Memory 内 bundle（=本 bundle）虽非 Load，但 redirect/flush
  //     之类全局 stall 仍可能导致 MemRefDff 不接受 → 实测 v18 baseline 已稳定，不重复构造。
  val anyLoadInExBundle = (0 until CPUConfig.executeWidth).map { k =>
    val ln = uRRExDff.out.bits.lanes(k)
    uRRExDff.out.bits.validMask(k) && ln.type_decode_together(4)
  }.reduce(_ || _)
  // [stale-base-register 修复] 仅检查当前 RR/Ex bundle 是否含 Load 不足以保证 N+3 不变量：
  // 若 ExMemDff 或 Memory 阶段 bundle 中存在 Load，且其因 MSHR 容量/older-unknown 触发
  // memStall，则下游反压会让"本拍 lui 等 ALU 生产者"虽完成 Ex→ExMemDff 推进，但下一拍
  // 在 ExMemDff→Memory 通路被卡住，PRF 写时点从 N+2 后移到 N+3 甚至更晚，导致同拍 β-wake
  // 唤醒的消费者在 N+2 拍 ReadReg 读 PRF 时既读不到新值（PRF 尚未写）也吃不到同拍 bypass
  // （write 还没来），结果读到 stale 旧值（典型表现：lui x15,0x10 → lw … 1372(x15) 把 x15
  // 读成 0，导致访存地址错配）。
  // 解决：将 β-wake 的"downstream 无 Load"门控扩展到 ExMemDff 与 Memory 两级 bundle。
  val anyLoadInMemDff = (0 until CPUConfig.memoryWidth).map { k =>
    val ln = uExMemDff.out.bits.lanes(k)
    uExMemDff.out.valid && uExMemDff.out.bits.validMask(k) && ln.type_decode_together(4)
  }.reduce(_ || _)
  val downstreamLoadStall = anyLoadInMemDff
  val exAdvance = uRRExDff.out.fire && uMemory.in.ready && !anyLoadInExBundle
  for (k <- 0 until CPUConfig.executeWidth) {
    val exLane  = uRRExDff.out.bits.lanes(k)
    val exValid = exAdvance && uRRExDff.out.bits.validMask(k)
    val exIsLoad = exLane.type_decode_together(4)
    // [v20 P0+P1 安全版] βwake 启用范围：lane0、lane2、lane3 三档放行；lane1 暂保留禁用。
    //   - lane0：Full lane，但 anyLoadInExBundle 已隔离同拍 Load 场景；
    //   - lane2/lane3：ALU-only，本身永不可能是 Load。
    //   - lane1：保持禁用 = v19.3 不变量 I-D（lane1 βwake 未独立验证）。
    // [stale-base 修复] 在原"当前 bundle 无 Load"基础上，再加 downstreamLoadStall 门控：
    //   下游（ExMemDff / Memory）只要存在 Load，就可能因 MSHR/older-unknown 反压上游，
    //   破坏 N+3 PRF-write 不变量。这一拍直接禁 β-wake，让 γ-wake / refresh-wake 接管，
    //   保守但正确（仅在极少数 Load 在飞行的拍次失去 1 拍前递收益）。
    val betaEnable = (k == 2 || k == 3).B && !downstreamLoadStall
    uIssueQueue.wakeup(CPUConfig.refreshWidth + CPUConfig.memoryWidth + k).valid :=
      betaEnable && exValid && exLane.regWriteEnable && !exIsLoad && (exLane.pdst =/= 0.U)
    uIssueQueue.wakeup(CPUConfig.refreshWidth + CPUConfig.memoryWidth + k).pdst := exLane.pdst
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
    uBCT.io.refreshAddr(k)  := effRefPdst(k)
  }

  // Store 提交路径（Phase A.2：Vec(K) 多端口，K = storeLanes.size = 2）：
  //   ROB 暴露 headIsStoreVec / headStoreSeqVec / commitStoreOrdinal，
  //   MyCPU 从 commitWidth 个 ROB lane 中按顺序挑出最多 K 个 store 候选（ordinal 0..K-1），
  //   分别送到 sqEnq(0..K-1) 与 SB.commit(0..K-1)。
  //   ROB.commitBlocked(m) 反馈第 m 个 store 是否被 AXISQ 拒收。
  val storeLanesK = LaneCapability.storeLanes.size

  // 对每个 store ordinal m，找出 ROB 中"本拍是该 ordinal 的那条 lane"，把其 storeSeq 送入 SB / AXISQ。
  // headIsStoreVec(i) 已包含 headLaneReadyHere(i)；其语义是"该 lane 是 store 且已通过 commit 候选条件（忽略 AXISQ 反压）"。
  val storeCandidateSeq   = Wire(Vec(storeLanesK, UInt(CPUConfig.storeSeqWidth.W)))
  val storeCandidateValid = Wire(Vec(storeLanesK, Bool()))
  for (m <- 0 until storeLanesK) {
    val matchVec = VecInit((0 until CPUConfig.commitWidth).map { i =>
      uROB.headIsStoreVec(i) && (uROB.commitStoreOrdinal(i) === m.U)
    })
    storeCandidateValid(m) := matchVec.asUInt.orR
    val pickIdx = PriorityEncoder(matchVec.asUInt)
    storeCandidateSeq(m) := uROB.headStoreSeqVec(pickIdx)
  }

  for (m <- 0 until storeLanesK) {
    uStoreBuffer.commit(m).valid    := storeCandidateValid(m)
    uStoreBuffer.commit(m).storeSeq := storeCandidateSeq(m)
    sqEnq(m).valid    := storeCandidateValid(m) && uStoreBuffer.commit(m).entryValid
    sqEnq(m).bits.addr := uStoreBuffer.commit(m).addr
    sqEnq(m).bits.data := uStoreBuffer.commit(m).data
    sqEnq(m).bits.mask := uStoreBuffer.commit(m).mask
    sqEnq(m).bits.wstrb := uStoreBuffer.commit(m).wstrb
    sqEnq(m).bits.wdata := uStoreBuffer.commit(m).wdata
    sqEnq(m).bits.storeSeq := storeCandidateSeq(m)
    uStoreBuffer.commit(m).enqSuccess := sqEnq(m).fire

    // commitBlocked(m): 第 m 个 store 候选存在（valid 已经过 SB.entryValid 检查）但未被 AXISQ 接受。
    // 注意：若 storeCandidateValid(m)=true 但 SB.entryValid=false（地址未就绪），sqEnq.valid=false → 永远不 fire。
    // 这种情况下也必须阻塞 ROB commit；故 commitBlocked 用 candidate 是否 fire 来判定，而不只看 sqEnq.valid。
    uROB.commitBlocked(m) := storeCandidateValid(m) && !sqEnq(m).fire
  }

  // Commit 观测信号（供 difftest 使用）—— 多 lane 对外输出
  io.commit_count := uROB.commitCount
  for (i <- 0 until CPUConfig.commitWidth) {
    io.commit(i).pc        := uROB.commit(i).pc
    io.commit(i).is_store  := uROB.commit(i).valid && uROB.commit(i).isStore
    io.commit(i).reg_wen   := uROB.commit(i).regWen
    io.commit(i).reg_waddr := uROB.commit(i).rd
    io.commit(i).reg_wdata := uROB.commit(i).regWBData
    io.commit(i).store_ordinal := uROB.commitStoreOrdinal(i)
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
