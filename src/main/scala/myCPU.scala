package mycpu

import chisel3._
import chisel3.util.log2Ceil
import mycpu.dataLane._
import mycpu.device._

// ============================================================================
// myCPU —— 8 级流水线 RISC-V RV32I 处理器核心
// ============================================================================
// 流水线结构（从前到后）：
//   Fetch(4,BPU) → FetchBuffer(4in/4out) → Decode(4-wide) → [DecRenDff]
//   → Rename(4-wide,ROB alloc) → RenameBuffer(4in/1out) → Dispatch(1-wide,load-use)
//   → [DispExDff] → Execute(1) → [ExMemDff] → MEM(1,redirect) → [MemWbDff]
//   → WB(1) → Commit(ROB)
//
// 关键设计点：
//   1. Fetch 每周期从 IROM 取 4 条指令（128 bit），BPU 在 Fetch 内完成轻量预译码和分支预测
//   2. Decode 4-wide：4 路并行译码，输出 DecodePacket
//   3. Rename 4-wide：占位级，完成 ROB 4-wide 分配（无 PRF/RAT）
//   4. RenameBuffer：16 深环形缓冲区，4-in/1-out，桥接 4-wide 前端与 1-wide 后端
//   5. Dispatch 1-wide：从 RenameBuffer 取指令，读 RegFile，Load-Use 冒险检测
//   6. ROB 支持每周期 4 条分配、MEM 阶段回滚（rollback）
//   7. 分支纠错在 MEM 阶段发出 redirect，PC 优先级：MEM redirect > Fetch predict > sequential
//   8. 数据旁路（Forwarding）共 3 级：MEM / WB / Commit
//   9. Mispredict 时冲刷：FetchBuffer / DecRenDff / RenameBuffer / DispExDff / ExMemDff + ROB 回滚
// ============================================================================
class myCPU extends Module {
  val io = IO(new Bundle {
    // ---- IROM 指令存储器接口 ----
    val inst_addr_o = Output(UInt(14.W))
    val inst_i = Input(UInt(128.W))

    // ---- DRAM 数据存储器读端口（MEM 阶段 Load 使用）----
    val ram_addr_o  = Output(UInt(32.W))
    val ram_mask_o  = Output(UInt(3.W))
    val ram_rdata_i = Input(UInt(32.W))

    // ---- DRAM 写端口（仅 Commit 阶段 Store 才写入）----
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
  val memRedirectValid = Wire(Bool())     // MEM 阶段重定向使能（分支预测错误）
  val memRedirectAddr  = Wire(UInt(32.W)) // MEM 阶段重定向地址
  val memRedirectRobIdx = Wire(UInt(CPUConfig.robPtrWidth.W)) // 误预测指令的 ROB 指针

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
  // 注意：Fetch BPU 预测跳转时 **不** 冲刷 FetchBuffer！
  // 原因：跳转指令本身在当前 FetchPacket 中，validMask 已正确截断后续槽位，
  //       如果在同一周期冲刷 FetchBuffer，会导致跳转指令自身无法入队而丢失。
  //       FetchBuffer 仅在 MEM 重定向时冲刷（清除错误路径指令）。
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
  // ============ Decode → Rename 流水寄存器 ============
  // =====================================================
  val uDecRenDff = Module(new BaseDff(new Decode_Rename_Payload, supportFlush = true))
  uDecRenDff.in <> uDecode.out
  uDecRenDff.flush.get := memRedirectValid

  // =====================================================
  // ============ Rename（4-wide 占位级，ROB 分配）============
  // =====================================================
  val uRename = Module(new Rename)
  uRename.in <> uDecRenDff.out
  uRename.flush := memRedirectValid

  // ROB 实例化
  val uROB = Module(new ROB)
  uROB.alloc <> uRename.robAlloc

  // =====================================================
  // ============ RenameBuffer（4-in / 1-out 环形缓冲区）============
  // =====================================================
  val uRenameBuffer = Module(new RenameBuffer)
  uRenameBuffer.enq <> uRename.out
  uRenameBuffer.flush := memRedirectValid

  // =====================================================
  // ============ Dispatch（1-wide，Load-Use 检测）============
  // =====================================================
  val uDisp = Module(new Dispatch)
  uDisp.in <> uRenameBuffer.deq
  uDisp.flush := memRedirectValid

  // ---- 寄存器堆（32 个 32 位通用寄存器）----
  val uREG = Module(new REG)
  uREG.io.reg_raddr1_i := uDisp.regRead.raddr1
  uREG.io.reg_raddr2_i := uDisp.regRead.raddr2
  uDisp.regRead.rdata1 := uREG.io.reg_rdata1_o
  uDisp.regRead.rdata2 := uREG.io.reg_rdata2_o

  // =====================================================
  // ============ Dispatch → Execute 流水线寄存器 ============
  // =====================================================
  val uDispExDff = Module(new BaseDff(new Dispatch_Execute_Payload, supportFlush = true))
  uDispExDff.in <> uDisp.out
  uDispExDff.flush.get := memRedirectValid

  // ---- Load-Use 冒险检测信号连接 ----
  uDisp.hazard.ex_rd     := uDispExDff.out.bits.inst(11, 7)
  uDisp.hazard.ex_isLoad := uDispExDff.out.bits.type_decode_together(4)
  uDisp.hazard.ex_valid  := uDispExDff.out.valid

  // =====================================================
  // ============ Execute（执行，ALU + 分支验证）============
  // =====================================================
  val uExecute = Module(new Execute)
  uExecute.in <> uDispExDff.out

  // ---- BHT 更新：Execute 阶段得到分支实际结果后回写 BHT ----
  if (CPUConfig.useBHT) { // MEM redirect 时抑制更新，防止错误路径的分支污染 BHT
    uBHT.get.io.update_valid := uExecute.bht_update.get.valid && !memRedirectValid
    uBHT.get.io.update_idx   := uExecute.bht_update.get.idx
    uBHT.get.io.update_taken := uExecute.bht_update.get.taken
  }

  // =====================================================
  // ============ Execute → MEM 流水线寄存器 ============
  // =====================================================
  val uExMemDff = Module(new BaseDff(new Execute_MEM_Payload, supportFlush = true))
  uExMemDff.in <> uExecute.out
  uExMemDff.flush.get := memRedirectValid

  // =====================================================
  // ============ MEM（访存 + 分支纠错重定向）============
  // =====================================================
  val uMEM = Module(new MEM)
  uMEM.in <> uExMemDff.out
  io.ram_addr_o := uMEM.io.ram_addr_o
  io.ram_mask_o := uMEM.io.ram_mask_o
  uMEM.io.ram_rdata_i := io.ram_rdata_i

  // MEM 阶段重定向信号
  memRedirectValid   := uMEM.redirect.valid
  memRedirectAddr    := uMEM.redirect.addr
  memRedirectRobIdx  := uMEM.redirect.robIdx

  // ROB 回滚：MEM redirect 时将 tail 回滚到误预测指令之后
  uROB.rollback.valid  := memRedirectValid
  uROB.rollback.robIdx := memRedirectRobIdx

  // =====================================================
  // ============ MEM → WB 流水线寄存器 ============
  // =====================================================
  // 注意：MemWbDff 不 flush！MEM 中的误预测指令需要正常流到 WB 完成标记
  val uMemWbDff = Module(new BaseDff(new MEM_WB_Payload))
  uMemWbDff.in <> uMEM.out

  // =====================================================
  // ============ WB（写回，标记 ROB 完成）============
  // =====================================================
  val uWB = Module(new WB)
  uWB.in <> uMemWbDff.out
  uROB.wb <> uWB.robWb

  // =====================================================
  // ============ Commit（提交，从 ROB 头部取）============
  // =====================================================
  // 寄存器写回
  uREG.io.reg_waddr_i := uROB.commit.rd
  uREG.io.reg_wdata_i := uROB.commit.result
  uREG.io.reg_wen_i   := uROB.commit.regWen

  // Store 写回 DRAM（只有 Commit 时才真正写存储器）
  io.commit_ram_wen_o   := uROB.commit.valid && uROB.commit.isStore && !uROB.commit.mispredict
  io.commit_ram_addr_o  := uROB.commit.storeAddr
  io.commit_ram_wdata_o := uROB.commit.storeData
  io.commit_ram_mask_o  := uROB.commit.storeMask

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
  //   1. MEM 级（Execute 前 1 条指令的结果，Load 除外——数据还没读回来）
  //   2. WB 级（Execute 前 2 条指令的结果，Load 数据已可用）
  //   3. Commit 级（同周期正在提交的指令结果）
  //   兜底：Dispatch 阶段读取的寄存器值（经 DispExDff 传入）

  // 第 1 级旁路：来自 Execute/MEM 流水线寄存器
  val wen_MEM = uExMemDff.out.valid && uExMemDff.out.bits.regWriteEnable &&
    !uExMemDff.out.bits.type_decode_together(4)  // Load 此时数据不可用
  uExecute.fwd.mem_rd   := uExMemDff.out.bits.inst_rd
  uExecute.fwd.mem_data := uExMemDff.out.bits.data
  uExecute.fwd.mem_wen  := wen_MEM

  // 第 2 级旁路：来自 MEM/WB 流水线寄存器（Load 数据已可用）
  val memWbWen = uMemWbDff.out.valid && uMemWbDff.out.bits.regWriteEnable
  uExecute.fwd.wb_rd   := uMemWbDff.out.bits.inst_rd
  uExecute.fwd.wb_data := uMemWbDff.out.bits.data
  uExecute.fwd.wb_wen  := memWbWen

  // 第 3 级旁路：Commit 级（同周期 ROB 正在提交的指令结果）
  uExecute.fwd.commit_rd   := uROB.commit.rd
  uExecute.fwd.commit_data := uROB.commit.result
  uExecute.fwd.commit_wen  := uROB.commit.regWen
}
