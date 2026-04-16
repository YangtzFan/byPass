package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig
import mycpu.device.ALU

// ============================================================================
// Execute（执行阶段）
// ============================================================================
// 主要功能：
//   1. 数据旁路转发（Forwarding）—— 从 4 个来源获取最新的寄存器值
//   2. ALU 计算 —— 算术/逻辑/地址计算
//   3. 分支验证 —— 比较 Fetch 阶段的预测与实际结果，检测 mispredict
//   4. BHT 更新 —— 将分支实际结果回写 BHT
//   5. Store 指令：将地址和数据写入 StoreBuffer（通过顶层连接）
//
// 无寄存器重命名版本：使用逻辑寄存器编号 rd(5-bit) 进行旁路匹配
// 数据旁路优先级（距离 Execute 最近的优先）：
//   Memory 级 > Refresh 级 > Commit 级 > ReadReg 读值（兜底）
// ============================================================================
class Execute extends Module {
  val in = IO(Flipped(Decoupled(new ReadReg_Execute_Payload)))  // 输入：来自 RRExDff（ReadReg 阶段）
  val out = IO(Decoupled(new Execute_Memory_Payload))           // 输出：送往 ExMemDff → Memory

  // ---- 数据旁路转发输入端口 ----
  // 无寄存器重命名版本：使用逻辑寄存器编号 rd(5-bit) 进行匹配
  val fwd = IO(new Bundle {
    // 第 1 级：来自 ExMemDff（Execute 前 1 条指令，即 Memory 阶段正在处理的指令）
    val mem_rd   = Input(UInt(5.W))   // 逻辑目标寄存器编号
    val mem_data = Input(UInt(32.W))  // 转发数据
    val mem_wen  = Input(Bool())      // 转发使能（非 Load 指令才可旁路）
    // 第 2 级：来自 MemRefDff（Memory → Refresh 流水线寄存器，Load 数据已可用）
    val ref_rd   = Input(UInt(5.W))
    val ref_data = Input(UInt(32.W))
    val ref_wen  = Input(Bool())
    // 第 3 级：ROB Commit（同周期正在提交的指令）
    val commit_rd   = Input(UInt(5.W))
    val commit_data = Input(UInt(32.W))
    val commit_wen  = Input(Bool())
  })

  // ---- BHT 更新端口（仅 useBHT 时生成）----
  private val bhtIdxWidth = log2Ceil(CPUConfig.bhtEntries)
  val bht_update = Option.when(CPUConfig.useBHT){IO(new Bundle {
    val valid = Output(Bool()) // 是否更新 BHT
    val idx   = Output(UInt(bhtIdxWidth.W)) // BHT 表项索引
    val taken = Output(Bool()) // 实际跳转结果
  })}

  // ---- 指令字段提取 ----
  val rd = in.bits.inst(11, 7)       // 逻辑目标寄存器
  val funct3 = in.bits.inst(14, 12)
  val rs1 = in.bits.inst(19, 15)     // 逻辑源寄存器 1（旁路匹配用）
  val rs2 = in.bits.inst(24, 20)     // 逻辑源寄存器 2（旁路匹配用）

  // ---- 指令类型 ----
  val uType = in.bits.type_decode_together(8) // LUI / AUIPC
  val jal   = in.bits.type_decode_together(7) // JAL
  val jalr  = in.bits.type_decode_together(6) // JALR
  val bType = in.bits.type_decode_together(5) // B 型分支
  val lType = in.bits.type_decode_together(4) // Load
  val iType = in.bits.type_decode_together(3) // I 型算术/逻辑
  val sType = in.bits.type_decode_together(2) // Store
  val rType = in.bits.type_decode_together(1) // R 型算术/逻辑
  val other = in.bits.type_decode_together(0) // FENCE / ECALL
  val lui = uType && in.bits.inst(5)          // LUI（opcode bit5=1）
  val auipc = uType && !in.bits.inst(5)       // AUIPC（opcode bit5=0）

  // ============================================================
  // 数据旁路转发选择 MUX
  // ============================================================
  // 无寄存器重命名版本：使用逻辑寄存器编号 rs1/rs2 进行旁路匹配
  // rd=x0 不产生旁路（x0 硬编码为 0）

  // rs1 的旁路匹配检测（按优先级从高到低）
  val fwd_rs1_from_mem    = fwd.mem_wen    && (fwd.mem_rd    =/= 0.U) && (fwd.mem_rd    === rs1)
  val fwd_rs1_from_ref    = fwd.ref_wen    && (fwd.ref_rd    =/= 0.U) && (fwd.ref_rd    === rs1)
  val fwd_rs1_from_commit = fwd.commit_wen && (fwd.commit_rd =/= 0.U) && (fwd.commit_rd === rs1)
  val actual_rdata1 = PriorityMux(Seq(
    fwd_rs1_from_mem    -> fwd.mem_data,       // 优先级最高：Memory 级（非 Load）
    fwd_rs1_from_ref    -> fwd.ref_data,       // 第 2 优先：Refresh 级（MemRefDff，含 Load 数据）
    fwd_rs1_from_commit -> fwd.commit_data,    // 第 3 优先：Commit 级（当前周期正在提交的指令）
    true.B              -> in.bits.src1Data     // 兜底：ReadReg 阶段从 REG 读取的值
  ))

  // rs2 的旁路匹配检测（同样优先级规则）
  val fwd_rs2_from_mem    = fwd.mem_wen    && (fwd.mem_rd    =/= 0.U) && (fwd.mem_rd    === rs2)
  val fwd_rs2_from_ref    = fwd.ref_wen    && (fwd.ref_rd    =/= 0.U) && (fwd.ref_rd    === rs2)
  val fwd_rs2_from_commit = fwd.commit_wen && (fwd.commit_rd =/= 0.U) && (fwd.commit_rd === rs2)
  val actual_rdata2 = PriorityMux(Seq(
    fwd_rs2_from_mem    -> fwd.mem_data,
    fwd_rs2_from_ref    -> fwd.ref_data,
    fwd_rs2_from_commit -> fwd.commit_data,
    true.B              -> in.bits.src2Data     // 兜底：ReadReg 阶段从 REG 读取的值
  ))

  // ============================================================
  // Execute 级 Load-Use 冒险检测
  // ============================================================
  // 当 ExMemDff 中的指令是 Load（mem_wen=false，因为 Load 数据此时不可用），
  // 且当前 Execute 指令依赖该 Load 的 rd 时，需要额外停顿 1 周期。
  // 正常情况下 Issue 级的检测已覆盖，但 Memory 停顿导致流水线压缩时需此检测。
  val memLoadIsValid = IO(Input(Bool()))  // ExMemDff.out.valid
  val memLoadIsLoad  = IO(Input(Bool()))  // ExMemDff 中的指令是否是 Load

  // 使用逻辑寄存器编号匹配，rd=x0 排除
  val exLoadUseHazard = memLoadIsValid && memLoadIsLoad && (fwd.mem_rd =/= 0.U) && in.valid &&
    ((fwd.mem_rd === rs1) || (fwd.mem_rd === rs2))

  // ============================================================
  // ALU 输入选择
  // ============================================================
  val iChoose30OrNot = iType && (funct3 === "b101".U)
  val rChoose30OrNot = rType && ((funct3 === "b101".U) || (funct3 === "b000".U))
  val aluA = Mux1H(Seq(
    (bType || jalr || lType || sType || iType || rType) -> actual_rdata1,
    (auipc || jal) -> in.bits.pc,
    (lui || other) -> 0.U(32.W)
  ))
  val aluB = Mux1H(Seq(
    (bType || rType) -> actual_rdata2,
    (auipc || jal || jalr || lType || sType || iType) -> in.bits.imm,
    (lui || other) -> 0.U(32.W)
  ))
  val aluCtrl = Mux1H(Seq(
    bType -> Mux(funct3(2), Cat(0.U(2.W), funct3(2, 1)), 8.U(4.W)),
    iType -> Cat(iChoose30OrNot && in.bits.inst(30), funct3),
    rType -> Cat(rChoose30OrNot && in.bits.inst(30), funct3),
    !(bType || iType || rType) -> 0.U(4.W)
  ))
  val uALU = Module(new ALU)
  uALU.io.a      := aluA
  uALU.io.b      := aluB
  uALU.io.ctrl   := aluCtrl
  uALU.io.enable := !(lui || other)

  // ============================================================
  // 分支跳转结果验证
  // ============================================================
  val branch_taken = bType && (funct3(0) ^ (!uALU.io.zero) ^ funct3(2))

  val b_imm_ex = Cat(Fill(20, in.bits.inst(31)), in.bits.inst(7), in.bits.inst(30, 25), in.bits.inst(11, 8), 0.U(1.W))
  val b_target_ex = in.bits.pc + b_imm_ex

  val jalr_imm = Cat(Fill(20, in.bits.inst(31)), in.bits.inst(31, 20))
  val jalr_target_raw = actual_rdata1 + jalr_imm
  val jalr_target = Cat(jalr_target_raw(31, 1), 0.U(1.W))

  // 分支预测错误检测
  val b_mispredict = bType && (branch_taken ^ in.bits.predict_taken)
  val b_correct_addr = Mux(branch_taken, b_target_ex, in.bits.pc + 4.U(32.W))
  val actual_taken_val = Mux(jalr, true.B, Mux(bType, branch_taken, false.B))
  val actual_target_addr = Mux(jalr, jalr_target, b_correct_addr)
  val isMispredict = in.valid && (b_mispredict || jalr) // JALR 始终视为 mispredict

  // BHT 更新
  if(CPUConfig.useBHT){
    bht_update.get.valid := in.valid && bType
    bht_update.get.idx   := in.bits.pc(bhtIdxWidth + 1, 2)
    bht_update.get.taken := branch_taken
  }

  // ============================================================
  // 输出结果
  // ============================================================
  out.bits.pc := in.bits.pc
  out.bits.inst_funct3 := Mux(lType || sType, funct3, 0.U)
  out.bits.inst_rd := Mux(uType || jal || jalr || lType || iType || rType, rd, 0.U) // 逻辑目标寄存器（旁路匹配 + REG 写入）
  out.bits.data := Mux1H(Seq(
    lui -> in.bits.imm,
    (auipc || lType || sType || iType || rType) -> uALU.io.result,
    (jal || jalr) -> (in.bits.pc + 4.U(32.W)),
    (bType || other) -> 0.U(32.W)
  ))
  out.bits.reg_rdata2           := Mux(sType, actual_rdata2, 0.U) // Store 的写入数据
  out.bits.type_decode_together := in.bits.type_decode_together
  out.bits.robIdx               := in.bits.robIdx
  out.bits.regWriteEnable       := in.bits.regWriteEnable
  out.bits.isBranch             := bType
  out.bits.isJump               := jal || jalr
  out.bits.predict_taken        := in.bits.predict_taken
  out.bits.predict_target       := in.bits.predict_target
  out.bits.actual_taken         := actual_taken_val
  out.bits.actual_target        := actual_target_addr
  out.bits.mispredict           := isMispredict
  out.bits.bht_meta             := in.bits.bht_meta
  out.bits.isSbAlloc            := in.bits.isSbAlloc
  out.bits.sbIdx                := in.bits.sbIdx
  out.bits.storeSeqSnap         := in.bits.storeSeqSnap

  // ---- 握手信号 ----
  in.ready := out.ready && !exLoadUseHazard
  out.valid := in.valid && !exLoadUseHazard
}
