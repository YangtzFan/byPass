package mycpu

import chisel3._
import chisel3.util.log2Ceil
import mycpu.dataLane._
import mycpu.device._

// ============================================================================
// myCPU —— 7 级流水线 RISC-V RV32I 处理器核心
// ============================================================================
// 流水线结构（从前到后）：
//   Fetch(4-wide) → FetchBuffer → Decode → Dispatch → Execute → MEM → WB → Commit(ROB)
//
// 关键设计点：
//   1. Fetch 每周期从 IROM 取 4 条指令（128 bit），存入 FetchBuffer
//   2. Decode / Dispatch / Execute / MEM / WB 目前宽度为 1（单发射）
//   3. 使用 ROB（重排序缓冲区）保证 **顺序提交**，只有 Commit 阶段才写 RegFile / Store 到 DRAM
//   4. 数据旁路（Forwarding）共 3 级：MEM / WB / Commit
//   5. 分支预测在 Decode 阶段完成，预测错误在 Commit 阶段才 flush 整条流水线
// ============================================================================
class myCPU extends Module {
  val io = IO(new Bundle {
    // ---- IROM 指令存储器接口 ----
    val inst_addr_o = Output(UInt(14.W))          // IROM 地址（128-bit 字地址，对应 PC[17:4]）
    val inst_i = Input(UInt(128.W))               // IROM 返回的 128 位数据（内含 4 条 32-bit 指令）

    // ---- DRAM 数据存储器读端口（MEM 阶段 Load 使用）----
    val ram_addr_o = Output(UInt(32.W))           // Load 地址
    val ram_mask_o = Output(UInt(3.W))            // 访存宽度掩码：00=字节, 01=半字, 10=字
    val ram_rdata_i = Input(UInt(32.W))           // Load 读回数据

    // ---- DRAM 写端口（仅 Commit 阶段 Store 才写入）----
    val commit_ram_wen_o   = Output(Bool())       // 写使能
    val commit_ram_addr_o  = Output(UInt(32.W))   // 写地址
    val commit_ram_wdata_o = Output(UInt(32.W))   // 写数据
    val commit_ram_mask_o  = Output(UInt(3.W))    // 写宽度掩码

    // ---- Commit 阶段观测端口（用于 difftest 对比仿真）----
    val commit_valid       = Output(Bool())       // 本周期是否有指令提交
    val commit_pc          = Output(UInt(32.W))   // 提交指令的 PC
    val commit_reg_wen     = Output(Bool())       // 是否写寄存器
    val commit_reg_waddr   = Output(UInt(5.W))    // 写入的寄存器编号
    val commit_reg_wdata   = Output(UInt(32.W))   // 写入的数据
  })

  // ---- 全局控制信号 ----
  val idJumpEnable = Wire(Bool())       // Decode 阶段的分支预测重定向使能
  val idJumpPredictAddr = Wire(UInt(32.W)) // Decode 阶段预测的跳转目标地址
  val commitFlushEnable = Wire(Bool())  // Commit 阶段发现预测错误时的 flush 使能
  val commitFlushAddr = Wire(UInt(32.W))// Commit 阶段计算出的正确跳转地址

  // =====================================================
  // ============ PC（程序计数器）============
  // =====================================================
  // PC 模块负责生成下一条要取的指令地址
  // 优先级：Commit flush > Decode 预测跳转 > 正常递增（+16，因为 4-wide 取指）
  val uPc = Module(new PC)
  uPc.in.jump_enable := idJumpEnable               // Decode 阶段预测需要跳转
  uPc.in.jump_predict_addr := idJumpPredictAddr     // 预测目标地址
  uPc.in.pipeline_flush_enable := commitFlushEnable // Commit 发现错误，需要 flush
  uPc.in.pipeline_flush_addr := commitFlushAddr     // flush 后的正确地址

  // =====================================================
  // ============ Fetch（取指，4 路宽度）============
  // =====================================================
  // 从 IROM 读出 128 位（4 条指令），打包成 FetchPacket 输出
  val uFetch = Module(new Fetch)
  uFetch.in <> uPc.out                // PC → Fetch：将 PC 值传入
  io.inst_addr_o := uFetch.io.inst_addr_o  // Fetch → IROM：输出 14 位地址
  uFetch.io.inst_i := io.inst_i            // IROM → Fetch：返回 128 位指令数据

  // =====================================================
  // ============ Fetch → Decode 流水寄存器 ============
  // =====================================================
  // 环形缓冲区，入队最多 4 条/周期（来自 Fetch），出队 1 条/周期（给 Decode）
  val uFetchBuffer = Module(new FetchBuffer)
  uFetchBuffer.enq <> uFetch.out           // Fetch → FetchBuffer
  uFetchBuffer.flush := commitFlushEnable || idJumpEnable  // 跳转或 flush 时清空缓冲区

  // =====================================================
  // ============ Decode（译码，宽度=1）============
  // =====================================================
  // 从 FetchBuffer 取 1 条指令，解码出操作类型、立即数、寄存器地址等
  val uDecode = Module(new Decode)
  uDecode.in <> uFetchBuffer.deq // FetchBuffer → Decode
  uDecode.bpu.flush := commitFlushEnable

  // ---- Decode 阶段的分支预测重定向 ----
  // 当 Decode 预测到 JAL/分支跳转时，立即重定向 PC 和清空 FetchBuffer
  // 注意：JALR 需要 rs1 数据，在 Decode 阶段无法确定目标，不进行预测
  idJumpEnable := uDecode.bpu.predict_jump && uDecode.out.fire && !commitFlushEnable
  idJumpPredictAddr := uDecode.bpu.predict_target

  // =====================================================
  // ============ Decode → Dispatch 流水寄存器 ============
  // =====================================================
  val uDecDispDff = Module(new BaseDff(new Decode_Dispatch_Payload, supportFlush = true))
  uDecDispDff.in <> uDecode.out
  uDecDispDff.flush.get := commitFlushEnable  // flush 时清除该级缓存

  // =====================================================
  // ============ Dispatch ============
  // =====================================================
  // 接收 1 条译码后的指令，分配 ROB 表项，读取寄存器，然后传给 Execute
  val uDisp = Module(new Dispatch)
  uDisp.in <> uDecDispDff.out
  uDisp.flush := commitFlushEnable // flush 信号连接，内部门控 out.valid 和 robAlloc.valid

  // ---- 寄存器堆（32 个 32 位通用寄存器）----
  // 提供 2 个读端口： Dispatch 读 rs1/rs2（作为转发链的兜底值）
  // 提供 1 个写端口： Commit 写回（只有提交的指令才真正写回寄存器）
  val uREG = Module(new REG)

  // ---- Dispatch 阶段寄存器堆读取 ----
  // 寄存器读取从 Decode 移至 Dispatch，减少值过时窗口（仅 1 拍即到达 Execute）
  uREG.io.reg_raddr1_i := uDisp.regRead.raddr1    // Dispatch 读 rs1
  uREG.io.reg_raddr2_i := uDisp.regRead.raddr2    // Dispatch 读 rs2
  uDisp.regRead.rdata1 := uREG.io.reg_rdata1_o    // 返回 rs1 数据
  uDisp.regRead.rdata2 := uREG.io.reg_rdata2_o    // 返回 rs2 数据

  // 异常处理只在 Commit 时才真正 flush，这样不会丢弃正确路径上的结果
  val uROB = Module(new ROB)
  uROB.io.alloc <> uDisp.robAlloc // Dispatch ↔ ROB 分配接口（使用 <> 批量连接打包的 ROBAllocIO）

  // =====================================================
  // ============ Dispatch → Execute 流水寄存器 ============
  // =====================================================
  val uDispExDff = Module(new BaseDff(new Dispatch_Execute_Payload, supportFlush = true))
  uDispExDff.in <> uDisp.out
  uDispExDff.flush.get := commitFlushEnable

  // ---- Load-Use 冒险检测信号连接 ----
  // 将 Dispatch→Execute 流水线寄存器中的 Load 指令信息传入 Decode 模块，
  // 由 Decode 内部完成停顿判断（为后续乱序多发射改造做准备，
  // 停顿逻辑集中在 Decode 方便扩展为多条 Load 的并行冒险检测）。
  uDecode.hazard.ex_rd     := uDispExDff.out.bits.inst(11, 7)             // Load 的目标寄存器
  uDecode.hazard.ex_isLoad := uDispExDff.out.bits.type_decode_together(4) // 是否是 Load 指令
  uDecode.hazard.ex_valid  := uDispExDff.out.valid                        // 该级是否有有效指令

  // =====================================================
  // ============ Execute（执行）============
  // =====================================================
  // ALU 计算 + 分支验证 + 地址计算
  val uExecute = Module(new Execute)
  uExecute.in <> uDispExDff.out

  // BHT 更新：Execute 阶段得到分支实际结果后，回写 BHT
  if(CPUConfig.useBHT){
    uDecode.bpu.update_valid.get := uExecute.bht_update.get.valid
    uDecode.bpu.update_idx.get   := uExecute.bht_update.get.idx
    uDecode.bpu.update_taken.get := uExecute.bht_update.get.taken
  }

  // =====================================================
  // ============ Execute → MEM 流水线寄存器 ============
  // =====================================================
  val uExMemDff = Module(new BaseDff(new Execute_MEM_Payload, supportFlush = true))
  uExMemDff.in <> uExecute.out
  uExMemDff.flush.get := commitFlushEnable

  // =====================================================
  // ============ MEM（访存）============
  // =====================================================
  // Load 指令在此阶段读取 DRAM；Store 只是携带地址/数据，延迟到 Commit 才写
  val uMEM = Module(new MEM)
  uMEM.in <> uExMemDff.out
  io.ram_addr_o := uMEM.io.ram_addr_o       // Load 地址输出到 DRAM
  io.ram_mask_o := uMEM.io.ram_mask_o       // Load 宽度掩码
  uMEM.io.ram_rdata_i := io.ram_rdata_i     // DRAM 返回的读数据

  // =====================================================
  // ============ MEM → WB 流水线寄存器 ============
  // =====================================================
  val uMemWbDff = Module(new BaseDff(new MEM_WB_Payload, supportFlush = true))
  uMemWbDff.in <> uMEM.out
  uMemWbDff.flush.get := commitFlushEnable

  // =====================================================
  // ============ WB（写回，标记 ROB 完成）============
  // =====================================================
  // 注意：WB **不**直接写 RegFile！它只把结果告诉 ROB，标记该表项 done
  // 真正写 RegFile 的操作在 Commit 阶段（保证顺序提交语义）
  val uWB = Module(new WB)
  uWB.in <> uMemWbDff.out
  uWB.flush := commitFlushEnable

  // WB → ROB 完成信号（使用 <> 批量连接打包的 ROBWbIO）
  // flush 门控已在 WB 内部处理（robWb.valid := in.valid && !flush）
  uROB.io.wb <> uWB.robWb

  // =====================================================
  // ============ Commit（提交，从 ROB 头部取）============
  // =====================================================
  // 只有 ROB 头部的指令 valid && done 时才提交
  // 寄存器写回（只有 Commit 才真正修改寄存器堆）
  uREG.io.reg_waddr_i := uROB.io.commit.rd
  uREG.io.reg_wdata_i := uROB.io.commit.result
  uREG.io.reg_wen_i   := uROB.io.commit.regWen

  // Store 写回 DRAM（只有 Commit 时才真正写存储器，避免推测执行污染内存）
  io.commit_ram_wen_o   := uROB.io.commit.valid && uROB.io.commit.isStore && !uROB.io.commit.mispredict
  io.commit_ram_addr_o  := uROB.io.commit.storeAddr
  io.commit_ram_wdata_o := uROB.io.commit.storeData
  io.commit_ram_mask_o  := uROB.io.commit.storeMask

  // Commit 观测信号（供 difftest 比较仿真框架使用）
  io.commit_valid     := uROB.io.commit.valid
  io.commit_pc        := uROB.io.commit.pc
  io.commit_reg_wen   := uROB.io.commit.regWen
  io.commit_reg_waddr := uROB.io.commit.rd
  io.commit_reg_wdata := uROB.io.commit.result

  // Commit 发现错误 → 产生 flush 信号，重定向 PC 到正确地址
  commitFlushEnable := uROB.io.flush.valid
  commitFlushAddr := uROB.io.flush.addr

  // =====================================================
  // ============ 数据旁路转发连接（Forwarding）============
  // =====================================================
  // 优先级（距离 Execute 越近的越优先）：
  //   1. MEM 级（Execute 前 1 条指令的结果，但 Load 除外——数据还没读回来）
  //   2. WB 级（Execute 前 2 条指令的结果，此时 Load 数据已可用）
  //   3. Commit 级（同周期正在提交的指令结果）
  //   兜底：Dispatch 阶段读取的寄存器值（经 DispExDff 传入）

  // 第 1 级旁路：来自 Execute/MEM 流水线寄存器
  val td_MEM = uExMemDff.out.bits.type_decode_together
  val wen_MEM = uExMemDff.out.valid && uExMemDff.out.bits.regWriteEnable &&
    !uExMemDff.out.bits.type_decode_together(4)  // Load 指令此时数据不可用，不能转发
  uExecute.fwd.mem_rd   := uExMemDff.out.bits.inst_rd   // 目标寄存器编号
  uExecute.fwd.mem_data := uExMemDff.out.bits.data       // ALU 计算结果
  uExecute.fwd.mem_wen  := wen_MEM                       // 转发使能

  // 第 2 级旁路：来自 MEM/WB 流水线寄存器（Load 数据此处已可用）
  val memWbWen = uMemWbDff.out.valid && uMemWbDff.out.bits.regWriteEnable
  uExecute.fwd.wb_rd   := uMemWbDff.out.bits.inst_rd
  uExecute.fwd.wb_data := uMemWbDff.out.bits.data
  uExecute.fwd.wb_wen  := memWbWen

  // 第 3 级旁路：Commit 级（同周期 ROB 正在提交的指令结果）
  uExecute.fwd.commit_rd   := uROB.io.commit.rd
  uExecute.fwd.commit_data := uROB.io.commit.result
  uExecute.fwd.commit_wen  := uROB.io.commit.regWen
}
