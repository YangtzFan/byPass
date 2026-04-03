package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig
import mycpu.device.ALU

// ============================================================================
// Execute（执行阶段）
// ============================================================================
// 主要功能：
//   1. 数据旁路转发（Forwarding）—— 从 3 个来源获取最新的寄存器值
//   2. ALU 计算 —— 算术/逻辑/地址计算
//   3. 分支验证 —— 比较 Fetch 阶段的预测与实际结果，检测 mispredict
//   4. BHT 更新 —— 将分支实际结果回写 BHT
//   5. Store 指令：将地址和数据写入 StoreBuffer（通过顶层连接）
//
// 数据旁路优先级（距离 Execute 最近的优先）：
//   Memory 级 > Refresh 级 > Commit 级 > ReadReg 阶段读取的寄存器值（兜底）
// ============================================================================
class Execute extends Module {
  val in = IO(Flipped(Decoupled(new ReadReg_Execute_Payload)))  // 输入：来自 RRExDff（ReadReg 阶段）
  val out = IO(Decoupled(new Execute_Memory_Payload))           // 输出：送往 ExMemDff → Memory

  // ---- 数据旁路转发输入端口 ----
  // 共 3 个转发来源，由 myCPU 顶层连接
  val fwd = IO(new Bundle {
    // 第 1 级：来自 ExMemDff（Execute 前 1 条指令，即 Memory 阶段正在处理的指令）
    val mem_rd   = Input(UInt(5.W))     // 目标寄存器编号
    val mem_data = Input(UInt(32.W))    // 转发数据
    val mem_wen  = Input(Bool())        // 转发使能
    // 第 2 级：来自 MemRefDff（Memory → Refresh 流水线寄存器，Load 数据已可用）
    val ref_rd   = Input(UInt(5.W))
    val ref_data = Input(UInt(32.W))
    val ref_wen  = Input(Bool())
    // 第 3 级：ROB Commit（同周期正在提交的指令）
    val commit_rd   = Input(UInt(5.W))
    val commit_data = Input(UInt(32.W))
    val commit_wen  = Input(Bool())
  })

  // ---- StoreBuffer 写入端口（Execute 阶段将 Store 地址和数据写入 StoreBuffer）----
  val sbWrite = IO(new Bundle {
    val valid = Output(Bool())                           // 写入使能
    val idx   = Output(UInt(CPUConfig.sbPtrWidth.W))     // 要写入的 StoreBuffer 表项指针
    val addr  = Output(UInt(32.W))                       // Store 地址
    val data  = Output(UInt(32.W))                       // Store 数据
    val mask  = Output(UInt(3.W))                        // Store 宽度掩码
  })

  // ---- BHT 更新端口（仅 useBHT 时生成）----
  private val bhtIdxWidth = log2Ceil(CPUConfig.bhtEntries)
  val bht_update = Option.when(CPUConfig.useBHT){IO(new Bundle {
    val valid = Output(Bool())           // 是否更新 BHT
    val idx   = Output(UInt(bhtIdxWidth.W)) // BHT 表项索引
    val taken = Output(Bool())           // 实际跳转结果
  })}

  // ---- 指令字段提取 ----
  val rd = in.bits.inst(11, 7)
  val funct3 = in.bits.inst(14, 12)
  val rs1 = in.bits.inst(19, 15)
  val rs2 = in.bits.inst(24, 20)

  // ---- 指令类型提取（从 9 位独热编码中取各位）----
  val uType = in.bits.type_decode_together(8)   // LUI / AUIPC
  val jal = in.bits.type_decode_together(7)     // JAL
  val jalr = in.bits.type_decode_together(6)    // JALR
  val bType = in.bits.type_decode_together(5)   // B 型分支
  val lType = in.bits.type_decode_together(4)   // Load
  val iType = in.bits.type_decode_together(3)   // I 型算术/逻辑
  val sType = in.bits.type_decode_together(2)   // Store
  val rType = in.bits.type_decode_together(1)   // R 型算术/逻辑
  val other = in.bits.type_decode_together(0)   // FENCE / ECALL

  val lui = uType && in.bits.inst(5)            // LUI（opcode bit5=1）
  val auipc = uType && !in.bits.inst(5)         // AUIPC（opcode bit5=0）

  // ============================================================
  // 数据旁路转发选择 MUX
  // ============================================================
  // 兜底值来自 ReadReg 阶段的寄存器堆读取（经 RRExDff 传入）

  // rs1 的旁路匹配检测（按优先级从高到低）
  val fwd_rs1_from_mem = fwd.mem_wen && (fwd.mem_rd =/= 0.U) && (fwd.mem_rd === rs1)
  val fwd_rs1_from_ref = fwd.ref_wen && (fwd.ref_rd =/= 0.U) && (fwd.ref_rd === rs1)
  val fwd_rs1_from_commit = fwd.commit_wen && (fwd.commit_rd =/= 0.U) && (fwd.commit_rd === rs1)
  val actual_rdata1 = PriorityMux(Seq(
    fwd_rs1_from_mem    -> fwd.mem_data,       // 优先级最高：Memory 级
    fwd_rs1_from_ref    -> fwd.ref_data,       // 第 2 优先：Refresh 级
    fwd_rs1_from_commit -> fwd.commit_data,    // 第 3 优先：Commit 级
    true.B              -> in.bits.src1Data    // 兜底：ReadReg 阶段读取的值
  ))

  // rs2 的旁路匹配检测（同样优先级规则）
  val fwd_rs2_from_mem = fwd.mem_wen && (fwd.mem_rd =/= 0.U) && (fwd.mem_rd === rs2)
  val fwd_rs2_from_ref = fwd.ref_wen && (fwd.ref_rd =/= 0.U) && (fwd.ref_rd === rs2)
  val fwd_rs2_from_commit = fwd.commit_wen && (fwd.commit_rd =/= 0.U) && (fwd.commit_rd === rs2)
  val actual_rdata2 = PriorityMux(Seq(
    fwd_rs2_from_mem    -> fwd.mem_data,
    fwd_rs2_from_ref    -> fwd.ref_data,
    fwd_rs2_from_commit -> fwd.commit_data,
    true.B              -> in.bits.src2Data    // 兜底：ReadReg 阶段读取的值
  ))

  // ============================================================
  // ALU 输入选择
  // ============================================================
  // ALU 控制信号：是否需要查看 inst[30]（区分 ADD/SUB、SRL/SRA 等）
  val iChoose30OrNot = iType && (funct3 === "b101".U)
  val rChoose30OrNot = rType && ((funct3 === "b101".U) || (funct3 === "b000".U))
  val aluA = MuxCase(0.U(32.W), Seq( // ALU 操作数 A：B/JALR/Load/Store/I/R 型用 rs1，AUIPC/JAL 用 PC
    (bType || jalr || lType || sType || iType || rType) -> actual_rdata1,
    (auipc || jal) -> in.bits.pc
  ))
  val aluB = MuxCase(0.U(32.W), Seq( // ALU 操作数 B：B/R 型用 rs2，其余用立即数
    (bType || rType) -> actual_rdata2,
    (auipc || jal || jalr || lType || sType || iType) -> in.bits.imm
  ))
  val aluCtrl = MuxCase(0.U(4.W), Seq( // ALU 控制信号：4 位 {funct7[5](部分), funct3}
    bType -> Mux(funct3(2), Cat(0.U(2.W), funct3(2, 1)), 8.U(4.W)), // 分支比较
    iType -> Cat(iChoose30OrNot && in.bits.inst(30), funct3),
    rType -> Cat(rChoose30OrNot && in.bits.inst(30), funct3)
  ))
  val uALU = Module(new ALU)
  uALU.io.a      := aluA
  uALU.io.b      := aluB
  uALU.io.ctrl   := aluCtrl
  uALU.io.enable := !(lui || other)  // LUI 直接用立即数，不需要 ALU

  // ============================================================
  // 分支跳转结果验证
  // ============================================================
  // 根据 ALU 比较结果和 funct3 判断分支是否实际跳转
  val branch_taken = bType && (funct3(0) ^ (!uALU.io.zero) ^ funct3(2))

  // 重新计算 B 型分支目标地址（独立于 Decode 的计算，使用 EX 阶段实际数据）
  val b_imm_ex = Cat(Fill(20, in.bits.inst(31)), in.bits.inst(7), in.bits.inst(30, 25), in.bits.inst(11, 8), 0.U(1.W))
  val b_target_ex = in.bits.pc + b_imm_ex

  // JALR 目标地址 = rs1 + 符号扩展(imm12)，最低位清零
  val jalr_imm = Cat(Fill(20, in.bits.inst(31)), in.bits.inst(31, 20))
  val jalr_target_raw = actual_rdata1 + jalr_imm
  val jalr_target = Cat(jalr_target_raw(31, 1), 0.U(1.W))

  // 分支预测错误检测
  val b_mispredict = bType && (branch_taken ^ in.bits.predict_taken)  // B 型：实际 ≠ 预测
  val b_correct_addr = Mux(branch_taken, b_target_ex, in.bits.pc + 4.U(32.W)) // 正确的下一条 PC
  val actual_taken_val = Mux(jalr, true.B, Mux(bType, branch_taken, false.B))
  val actual_target_addr = Mux(jalr, jalr_target, b_correct_addr)
  val isMispredict = in.valid && (b_mispredict || jalr) // JALR 始终视为 mispredict

  // BHT 更新：将分支的实际结果回写 BHT 表
  if(CPUConfig.useBHT){
    bht_update.get.valid := in.valid && bType              // 只有 B 型分支才更新
    bht_update.get.idx   := in.bits.pc(bhtIdxWidth + 1, 2) // 用 PC 低位索引
    bht_update.get.taken := branch_taken                   // 实际跳转结果
  }

  // ============================================================
  // StoreBuffer 写入（Execute 阶段计算出 Store 地址和数据后写入 StoreBuffer）
  // ============================================================
  sbWrite.valid := in.valid && sType && in.bits.isSbAlloc
  sbWrite.idx   := in.bits.sbIdx
  sbWrite.addr  := uALU.io.result                // Store 地址 = rs1 + 立即数（ALU 计算结果）
  sbWrite.data  := actual_rdata2                  // Store 数据 = rs2 的值
  sbWrite.mask  := funct3                         // Store 宽度掩码 = funct3

  // ============================================================
  // 输出结果
  // ============================================================
  out.bits.pc := in.bits.pc
  out.bits.inst_funct3 := Mux(lType || sType, funct3, 0.U) // Load/Store 需要 funct3
  out.bits.inst_rd := Mux(uType || jal || jalr || lType || iType || rType, rd, 0.U) // 有写回的指令
  out.bits.data := MuxCase(0.U(32.W), Seq(
    lui -> in.bits.imm,                                    // LUI：直接输出高 20 位立即数
    (auipc || lType || sType || iType || rType) -> uALU.io.result, // ALU 结果
    (jal || jalr) -> (in.bits.pc + 4.U(32.W))              // JAL/JALR：链接地址 = PC+4
  ))
  out.bits.reg_rdata2 := Mux(sType, actual_rdata2, 0.U)    // Store 的写入数据（保留用于后续阶段）
  out.bits.type_decode_together := in.bits.type_decode_together
  out.bits.robIdx := in.bits.robIdx
  out.bits.regWriteEnable := in.bits.regWriteEnable
  out.bits.isBranch := bType
  out.bits.isJump := jal || jalr
  out.bits.predict_taken := in.bits.predict_taken
  out.bits.predict_target := in.bits.predict_target
  out.bits.actual_taken := actual_taken_val
  out.bits.actual_target := actual_target_addr
  out.bits.mispredict := isMispredict
  out.bits.bht_meta := in.bits.bht_meta
  out.bits.sbIdx := in.bits.sbIdx
  out.bits.isSbAlloc := in.bits.isSbAlloc

  in.ready := out.ready
  out.valid := in.valid
}
