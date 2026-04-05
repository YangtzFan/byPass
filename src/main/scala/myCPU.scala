package mycpu

import chisel3._
import chisel3.util.log2Ceil
import mycpu.dataLane._
import mycpu.device._

// ============================================================================
// myCPU —— 10 级流水线 RISC-V RV32I 处理器核心（乱序四发射过渡版本）
// ============================================================================
// 流水线结构（从前到后）：
//   Fetch(4,BPU) → [FetchBuffer(4in/4out)] → Decode(4) → [DecRenDff]
//   → Rename(4) → [RenDisDff] → Dispatch(4,ROB alloc) → [IssueQueue(4in/4out)]
//   → Issue(1,load-use) → [IssRRDff] → ReadReg(1) → [RRExDff]
//   → Execute(1,branch verify) → [ExMemDff] → Memory(1,redirect) → [MemRefDff]
//   → Refresh(1) → Commit(ROB)
//
// 关键设计点：
//   1. Fetch 每周期从 IROM 取 4 条指令（128 bit），BPU 在 Fetch 内完成轻量预译码和分支预测
//   2. Decode 4-wide：4 路并行译码，输出 Decode_Rename_Payload
//   3. Rename 4-wide：纯打拍占位级（后续实现寄存器重命名），计算 regWriteEnable
//   4. RenDisDff：4-wide 普通流水寄存器（替代原 RenameBuffer）
//   5. Dispatch 4-wide：ROB 4-wide 分配 + StoreBuffer 分配；
//      dispatch_n = min(valid_rename_n, rob_free_n, iq_free_n, sb_free_n)
//   6. IssueQueue：16 深环形缓冲区，4-in/4-out（当前仅用 1-out）
//   7. Issue 1-wide：从 IssueQueue 队首顺序取 1 条指令，含 Load-Use 冒险检测
//   8. ReadReg：专用阶段读取寄存器堆，获取 rs1/rs2 值作为旁路兜底
//   9. Execute：ALU 计算 + 分支验证
//  10. Memory：Load 访存 + + Store 数据写入 StoreBuffer + 分支纠错重定向（冲刷前级 + ROB 回滚）
//  11. Refresh：更新 ROB 完成状态和结果字段，不更新架构状态
//  12. Commit：从 ROB 头部提交 —— 写寄存器堆 + 通知 StoreBuffer 将 Store 写入内存
//  13. 数据旁路（Forwarding）3 级：Memory / Refresh / Commit → Execute，兜底来自 ReadReg
//  14. StoreBuffer（深度 32）：Dispatch 分配、Execute 写入地址/数据、Memory Load 查询转发、Commit 写存
//  15. Mispredict 时冲刷：FetchBuffer / DecRenDff / RenDisDff / IssueQueue / IssRRDff / RRExDff / ExMemDff + ROB/SB 回滚
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

    // ---- DRAM 写端口（仅 Commit 阶段 Store 才写入，通过 StoreBuffer）----
    val commit_ram_wen_o   = Output(Bool())
    val commit_ram_addr_o  = Output(UInt(32.W))
    val commit_ram_wdata_o = Output(UInt(32.W))
    val commit_ram_mask_o  = Output(UInt(3.W))

    // ---- Commit 阶段观测端口（用于 difftest 对比仿真）----
    val commit_valid     = Output(Bool())
    val commit_pc        = Output(UInt(32.W))
    val commit_reg_wen   = Output(Bool())
    val commit_reg_waddr = Output(UInt(5.W))
    val commit_reg_wdata = Output(UInt(32.W))
  })

  // =====================================================
  // ============ 全局控制信号 ============
  // =====================================================
  val memRedirectValid   = Wire(Bool())     // Memory 阶段重定向使能（分支预测错误）
  val memRedirectAddr    = Wire(UInt(32.W)) // Memory 阶段重定向地址
  val memRedirectRobIdx  = Wire(UInt(CPUConfig.robPtrWidth.W)) // 误预测指令的 ROB 指针

  val fetchPredictJump   = Wire(Bool())       // Fetch 阶段 BPU 预测跳转使能
  val fetchPredictTarget = Wire(UInt(32.W))   // Fetch 阶段 BPU 预测跳转目标

  // =====================================================
  // ============ PC（程序计数器）============
  // =====================================================
  val uPc = Module(new PC)
  uPc.in.mem_redirect_enable  := memRedirectValid
  uPc.in.mem_redirect_addr    := memRedirectAddr
  uPc.in.fetch_predict_enable := fetchPredictJump && !memRedirectValid
  uPc.in.fetch_predict_addr   := fetchPredictTarget

  // =====================================================
  // ============ Fetch（取指 + BPU，4 路宽度）============
  // =====================================================
  val uFetch = Module(new Fetch)
  uFetch.in <> uPc.out
  io.inst_addr_o := uFetch.rom.inst_addr_o
  uFetch.rom.inst_i := io.inst_i

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
  // ============ Fetch → Decode 流水寄存器 ============
  // =====================================================
  // 注意：Fetch BPU 预测跳转时 **不** 冲刷 FetchBuffer
  // FetchBuffer 仅在 Memory 重定向时冲刷（清除错误路径指令）。
  val uFetchBuffer = Module(new FetchBuffer)
  uFetchBuffer.enq <> uFetch.out
  uFetchBuffer.flush := memRedirectValid

  // =====================================================
  // ============ Decode（4-wide 并行译码）============
  // =====================================================
  val uDecode = Module(new Decode)
  uDecode.in <> uFetchBuffer.deq
  uDecode.flush := memRedirectValid

  // =====================================================
  // ============ Decode → Rename 流水寄存器（DecRenDff）============
  // =====================================================
  val uDecRenDff = Module(new BaseDff(new Decode_Rename_Payload, supportFlush = true))
  uDecRenDff.in <> uDecode.out
  uDecRenDff.flush.get := memRedirectValid

  // =====================================================
  // ============ Rename（4-wide 纯打拍占位级）============
  // =====================================================
  val uRename = Module(new Rename)
  uRename.in <> uDecRenDff.out
  uRename.flush := memRedirectValid

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

  // ---- ROB 实例化 ----
  val uROB = Module(new ROB)
  // ---- StoreBuffer 实例化 ----
  val uStoreBuffer = Module(new StoreBuffer)
  // ---- Dispatch ↔ ROB 分配连接 ----
  uROB.alloc <> uDisp.robAlloc
  // ---- Dispatch ↔ StoreBuffer 分配连接 ----
  uDisp.sbAlloc.canAlloc := uStoreBuffer.alloc.canAlloc
  uDisp.sbAlloc.idxs     := uStoreBuffer.alloc.idxs
  uStoreBuffer.alloc.request := uDisp.sbAlloc.request

  // =====================================================
  // ============ IssueQueue（4-in / 4-out 环形缓冲区）============
  // =====================================================
  val uIssueQueue = Module(new IssueQueue)
  uIssueQueue.enq <> uDisp.out
  uIssueQueue.flush := memRedirectValid

  // ---- IssueQueue 空闲槽位数量反馈到 Dispatch 做流控 ----
  uDisp.iqFreeCount := uIssueQueue.freeCount

  // =====================================================
  // ============ Issue（当前 1-wide，Load-Use 冒险检测）============
  // =====================================================
  val uIssue = Module(new Issue)
  // IssueQueue 4-wide 出队 ↔ Issue 控制接口
  uIssue.iq.entries := uIssueQueue.deq.entries
  uIssue.iq.valid   := uIssueQueue.deq.valid
  uIssueQueue.deq.deqCount := uIssue.iq.deqCount
  uIssueQueue.deq.ready    := uIssue.iq.ready
  uIssue.flush := memRedirectValid

  // =====================================================
  // ============ Issue → ReadReg 流水线寄存器（IssRRDff）============
  // =====================================================
  val uIssRRDff = Module(new BaseDff(new Issue_ReadReg_Payload, supportFlush = true))
  uIssRRDff.in <> uIssue.out
  uIssRRDff.flush.get := memRedirectValid

  // =====================================================
  // ============ ReadReg（读取寄存器堆）============
  // =====================================================
  val uReadReg = Module(new ReadReg)
  uReadReg.in <> uIssRRDff.out

  // ---- 寄存器堆（32 个 32 位通用寄存器）----
  val uREG = Module(new REG)
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

  // ---- Load-Use 冒险检测信号连接 ----
  // Issue 阶段需要知道 RRExDff 中正在进入 Execute 的指令是否是 Load
  uIssue.hazard.ex_rd     := uRRExDff.out.bits.inst(11, 7)
  uIssue.hazard.ex_isLoad := uRRExDff.out.bits.type_decode_together(4)
  uIssue.hazard.ex_valid  := uRRExDff.out.valid

  // =====================================================
  // ============ Execute（执行，ALU + 分支验证 + StoreBuffer 写入）============
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

  // ---- StoreBuffer 写入连接（Memory 阶段将 Store 地址和数据写入 StoreBuffer）----
  uStoreBuffer.write.valid := uMemory.sbWrite.valid
  uStoreBuffer.write.idx   := uMemory.sbWrite.idx
  uStoreBuffer.write.addr  := uMemory.sbWrite.addr
  uStoreBuffer.write.data  := uMemory.sbWrite.data
  uStoreBuffer.write.mask  := uMemory.sbWrite.mask

  // ---- StoreBuffer 查询连接（Memory 阶段 Load 指令查询 Store-to-Load 转发）----
  uStoreBuffer.query.valid       := uMemory.sbQuery.valid
  uStoreBuffer.query.addr        := uMemory.sbQuery.addr
  uStoreBuffer.query.robIdx      := uMemory.sbQuery.robIdx
  uMemory.sbQuery.hit            := uStoreBuffer.query.hit
  uMemory.sbQuery.data           := uStoreBuffer.query.data
  uMemory.sbQuery.addrUnknown    := uStoreBuffer.query.addrUnknown

  // Memory 阶段重定向信号
  memRedirectValid   := uMemory.redirect.valid
  memRedirectAddr    := uMemory.redirect.addr
  memRedirectRobIdx  := uMemory.redirect.robIdx

  // ROB 回滚：Memory redirect 时将 tail 回滚到误预测指令之后
  uROB.rollback.valid  := memRedirectValid
  uROB.rollback.robIdx := memRedirectRobIdx

  // StoreBuffer 回滚：Memory redirect 时回滚
  uStoreBuffer.rollback.valid  := memRedirectValid
  uStoreBuffer.rollback.robIdx := memRedirectRobIdx

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
  // 寄存器写回（Commit 时更新架构状态）
  uREG.io.reg_waddr_i := uROB.commit.rd
  uREG.io.reg_wdata_i := uROB.commit.result
  uREG.io.reg_wen_i   := uROB.commit.regWen

  // Store 写回内存（通过 StoreBuffer 提交）
  // 当 ROB 提交一条 Store 指令时，通知 StoreBuffer 将该 Store 写入内存
  val storeCommitValid = uROB.commit.valid && uROB.commit.isStore && !uROB.commit.mispredict
  uStoreBuffer.commit.valid := storeCommitValid

  // DRAM 写端口：由 StoreBuffer 提供地址/数据/掩码
  io.commit_ram_wen_o   := storeCommitValid && uStoreBuffer.commit.canCommit
  io.commit_ram_addr_o  := uStoreBuffer.commit.addr
  io.commit_ram_wdata_o := uStoreBuffer.commit.data
  io.commit_ram_mask_o  := uStoreBuffer.commit.mask

  // Commit 观测信号（供 difftest 使用）
  io.commit_valid     := uROB.commit.valid
  io.commit_pc        := uROB.commit.pc
  io.commit_reg_wen   := uROB.commit.regWen
  io.commit_reg_waddr := uROB.commit.rd
  io.commit_reg_wdata := uROB.commit.result

  // =====================================================
  // ============ 数据旁路转发连接（Forwarding）============
  // =====================================================
  // 优先级（距离 Execute 越近的越优先）：
  //   1. Memory 级（Execute 前 1 条指令的结果，Load 除外——数据还没读回来）
  //   2. Refresh 级（Execute 前 2 条指令的结果，Load 数据已可用）
  //   3. Commit 级（同周期正在提交的指令结果）
  //   兜底：ReadReg 阶段读取的寄存器值（经 RRExDff 传入）

  // 第 1 级旁路：来自 ExMemDff（Memory 级）
  val wen_Memory = uExMemDff.out.valid && uExMemDff.out.bits.regWriteEnable &&
    !uExMemDff.out.bits.type_decode_together(4)  // Load 此时数据不可用
  uExecute.fwd.mem_rd   := uExMemDff.out.bits.inst_rd
  uExecute.fwd.mem_data := uExMemDff.out.bits.data
  uExecute.fwd.mem_wen  := wen_Memory

  // 第 2 级旁路：来自 MemRefDff（Refresh 级，Load 数据已可用）
  val refWen = uMemRefDff.out.valid && uMemRefDff.out.bits.regWriteEnable
  uExecute.fwd.ref_rd   := uMemRefDff.out.bits.inst_rd
  uExecute.fwd.ref_data := uMemRefDff.out.bits.data
  uExecute.fwd.ref_wen  := refWen

  // 第 3 级旁路：Commit 级（同周期 ROB 正在提交的指令结果）
  uExecute.fwd.commit_rd   := uROB.commit.rd
  uExecute.fwd.commit_data := uROB.commit.result
  uExecute.fwd.commit_wen  := uROB.commit.regWen
}
