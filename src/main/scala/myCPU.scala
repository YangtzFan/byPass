package mycpu

import chisel3._
import chisel3.util._
import mycpu.dataLane._
import mycpu.device._

// ============================================================================
// myCPU —— 10 级流水线 RISC-V RV32I 处理器核心（无寄存器重命名版本）
// ============================================================================
// 流水线结构（从前到后）：
//   Fetch(4,BPU) → [FetchBuffer(4in/4out)] → Decode(4) → [DecRenDff]
//   → Rename(4,仅计数) → [RenDisDff] → Dispatch(4,ROB alloc) → [IssueQueue(4in/4out)]
//   → Issue(1,load-use) → [IssRRDff] → ReadReg(1,REG read) → [RRExDff]
//   → Execute(1,branch verify) → [ExMemDff] → Memory(1,redirect) → [MemRefDff]
//   → Refresh(1,REG write) → Commit(ROB)
//
// 无寄存器重命名版本关键变更：
//   1. 使用 REG（32 个逻辑寄存器）替代 PRF（128 个物理寄存器）
//   2. 移除 RAT、RRAT、FreeList、ReadyTable、BranchCheckpointTable
//   3. 旁路转发使用逻辑寄存器 rd(5-bit) 匹配
//   4. 分支恢复仅重定向 PC + 回滚 ROB/StoreBuffer，不恢复任何 checkpoint
//   5. REG 写入在 Refresh 阶段完成（顺序单发射，错误路径指令不会到达 Refresh）
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
  val memRedirectStoreSeqSnap = Wire(UInt(CPUConfig.storeSeqWidth.W)) // 误预测指令的 storeSeqSnap
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
  // Fetch BPU 预测跳转信号
  fetchPredictJump   := uFetch.predict.jump
  fetchPredictTarget := uFetch.predict.target

  // =====================================================
  // ============ Fetch → Decode 流水寄存器（FetchBuffer）============
  // =====================================================
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
  // ============ Rename（4-wide，仅计算 validCount/storeCount）============
  // =====================================================
  // 无寄存器重命名版本：Rename 阶段不进行任何寄存器映射操作，
  // 仅透传译码结果并计算有效指令数和 Store 指令数，供 Dispatch 分配资源使用
  val uRename = Module(new Rename)
  uRename.in <> uDecRenDff.out
  uRename.flush := memRedirectValid

  // =====================================================
  // ============ Rename → Dispatch 流水寄存器（RenDisDff）============
  // =====================================================
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
  uIssueQueue.alloc <> uDisp.iqAlloc
  uIssueQueue.write := uDisp.out
  uIssueQueue.flush := memRedirectValid

  // =====================================================
  // ============ Issue（当前 1-wide，Load-Use 冒险检测）============
  // =====================================================
  val uIssue = Module(new Issue)
  uIssue.flush := memRedirectValid
  uIssue.iqIssue <> uIssueQueue.issue

  // =====================================================
  // ============ Issue → ReadReg 流水线寄存器（IssRRDff）============
  // =====================================================
  val uIssRRDff = Module(new BaseDff(new Issue_ReadReg_Payload, supportFlush = true))
  uIssRRDff.in <> uIssue.out
  uIssRRDff.flush.get := memRedirectValid
  // ---- Load-Use 冒险检测信号连接 ----
  // 无寄存器重命名版本：使用逻辑目标寄存器 rd(5-bit) 进行冒险匹配
  uIssue.hazard.rd          := uIssRRDff.out.bits.inst(11, 7) // 从 inst 字段提取 rd
  uIssue.hazard.isValidLoad := uIssRRDff.out.valid && uIssRRDff.out.bits.type_decode_together(4)

  // =====================================================
  // ============ ReadReg（从 REG 读取逻辑寄存器值）============
  // =====================================================
  val uReadReg = Module(new ReadReg)
  uReadReg.in <> uIssRRDff.out
  // ---- REG（逻辑寄存器堆，32 x 32-bit）----
  val uREG = Module(new REG)
  // ---- REG 读端口连接（按逻辑寄存器编号读取操作数）----
  uREG.io.reg_raddr1_i := uReadReg.regRead.raddr1
  uREG.io.reg_raddr2_i := uReadReg.regRead.raddr2
  uReadReg.regRead.rdata1 := uREG.io.reg_rdata1_o
  uReadReg.regRead.rdata2 := uREG.io.reg_rdata2_o

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
  // ---- BHT 更新 ----
  if (CPUConfig.useBHT) {
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

  // ---- StoreBuffer 连接 ----
  uStoreBuffer.write := uMemory.sbWrite
  uStoreBuffer.query <> uMemory.sbQuery
  uMemory.drainActive := uStoreBuffer.drain.valid

  // ---- Memory 阶段重定向信号 ----
  memRedirectValid        := uMemory.redirect.valid
  memRedirectAddr         := uMemory.redirect.addr
  memRedirectRobIdx       := uMemory.redirect.robIdx
  memRedirectStoreSeqSnap := uMemory.redirect.storeSeqSnap

  // ---- ROB 回滚 ----
  uROB.rollback.valid  := memRedirectValid
  uROB.rollback.robIdx := memRedirectRobIdx

  // ---- StoreBuffer 回滚 ----
  uStoreBuffer.rollback.valid        := memRedirectValid
  uStoreBuffer.rollback.storeSeqSnap := memRedirectStoreSeqSnap

  // =====================================================
  // ============ Memory → Refresh 流水线寄存器（MemRefDff）============
  // =====================================================
  // 注意：MemRefDff 不 flush！误预测指令需要正常流到 Refresh 完成标记
  val uMemRefDff = Module(new BaseDff(new Memory_Refresh_Payload))
  uMemRefDff.in <> uMemory.out

  // =====================================================
  // ============ Refresh（更新 ROB 完成状态 + REG 写入）============
  // =====================================================
  val uRefresh = Module(new Refresh)
  uRefresh.in <> uMemRefDff.out
  uROB.refresh <> uRefresh.robRefresh

  // ---- REG 写端口（Refresh 阶段写回执行结果）----
  // 顺序单发射下，错误路径指令不会到达 Refresh 阶段，因此在此写 REG 是安全的
  val refreshValid = uRefresh.robRefresh.valid && uRefresh.robRefresh.regWriteEnable
  uREG.io.reg_wen_i   := refreshValid
  uREG.io.reg_waddr_i := uRefresh.robRefresh.rd   // 逻辑目标寄存器编号
  uREG.io.reg_wdata_i := uRefresh.robRefresh.regWBData

  // =====================================================
  // ============ Commit（提交，从 ROB 头部取）============
  // =====================================================
  // 无寄存器重命名版本：Commit 不再更新 RRAT 或释放物理寄存器

  // Store 提交标记
  val storeCommitValid = uROB.commit.valid && uROB.commit.isStore && !uROB.commit.mispredict
  uStoreBuffer.commit.valid    := storeCommitValid
  uStoreBuffer.commit.storeSeq := uROB.commit.storeSeq

  // DRAM 写端口：由 StoreBuffer 的 Drain 逻辑驱动
  io.commit_ram_wen   := uStoreBuffer.drain.valid
  io.commit_ram_waddr := uStoreBuffer.drain.addr
  io.commit_ram_wdata := uStoreBuffer.drain.data
  io.commit_ram_wmask := uStoreBuffer.drain.mask

  // Commit 观测信号（供 difftest 使用）
  io.commit_valid     := uROB.commit.valid
  io.commit_pc        := uROB.commit.pc
  io.commit_reg_wen   := uROB.commit.regWen
  io.commit_reg_waddr := uROB.commit.rd        // 逻辑寄存器编号
  io.commit_reg_wdata := uROB.commit.regWBData

  // =====================================================
  // ============ 数据旁路转发连接（Forwarding）============
  // =====================================================
  // 无寄存器重命名版本：使用逻辑寄存器编号 rd(5-bit) 进行旁路匹配
  // 优先级（距离 Execute 越近的越优先）：
  //   1. Memory 级（ExMemDff，Load 除外——数据还没读回来）
  //   2. Refresh 级（MemRefDff，Load 数据已可用）
  //   3. Commit 级（同周期正在提交的指令结果）
  //   兜底：ReadReg 阶段读取的寄存器值

  // 第 1 级旁路：来自 ExMemDff（Memory 级）
  val wen_Memory = uExMemDff.out.valid && uExMemDff.out.bits.regWriteEnable &&
    !uExMemDff.out.bits.type_decode_together(4)  // Load 此时数据不可用，不能旁路
  uExecute.fwd.mem_rd   := uExMemDff.out.bits.inst_rd // 逻辑目标寄存器
  uExecute.fwd.mem_data := uExMemDff.out.bits.data
  uExecute.fwd.mem_wen  := wen_Memory

  // Execute 级 Load-Use 冒险检测信号
  uExecute.memLoadIsValid := uExMemDff.out.valid
  uExecute.memLoadIsLoad  := uExMemDff.out.bits.type_decode_together(4)

  // 第 2 级旁路：来自 MemRefDff（Refresh 级，Load 数据已可用）
  val refWen = uMemRefDff.out.valid && uMemRefDff.out.bits.regWriteEnable
  uExecute.fwd.ref_rd   := uMemRefDff.out.bits.inst_rd // 逻辑目标寄存器
  uExecute.fwd.ref_data := uMemRefDff.out.bits.data
  uExecute.fwd.ref_wen  := refWen

  // 第 3 级旁路：Commit 级（同周期 ROB 正在提交的指令结果）
  uExecute.fwd.commit_rd   := uROB.commit.rd        // 逻辑目标寄存器
  uExecute.fwd.commit_data := uROB.commit.regWBData
  uExecute.fwd.commit_wen  := uROB.commit.regWen
}
