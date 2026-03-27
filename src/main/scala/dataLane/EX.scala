import chisel3._
import chisel3.util._
import chisel3.util._

class EX extends Module {
  val in = IO(Flipped(Decoupled(new ID_EX_Payload)))
  val out = IO(Decoupled(new EX_MEM_Payload))

  // ---- 数据旁路转发输入端口 ----
  // 当前 EX 级的指令可能依赖前面尚未写回寄存器堆的指令结果，通过转发端口直接获取这些结果，避免读到过时的寄存器值。
  val fwd = IO(new Bundle {
    val mem_rd   = Input(UInt(5.W))  // EX/MEM 级间寄存器中指令的目标寄存器 rd
    val mem_data = Input(UInt(32.W)) // EX/MEM 级间寄存器中的计算结果
    val mem_wen  = Input(Bool())     // EX/MEM 级指令是否写寄存器（不含 Load，因其数据要到 MEM 才可用）
    val wb_rd   = Input(UInt(5.W))   // MEM/WB 级间寄存器中指令的目标寄存器 rd
    val wb_data = Input(UInt(32.W))  // MEM/WB 级间寄存器中的结果数据（含 Load 读出的内存数据）
    val wb_wen  = Input(Bool())      // MEM/WB 级指令是否写寄存器
  })

  // ---- 分支验证与流水线冲刷输出端口 ----
  val flush_io = IO(new Bundle {
    val enable = Output(Bool())          // 预测错误或 JALR，需冲刷流水线
    val redirect_addr = Output(UInt(32.W)) // 正确的 PC 目标地址
  })

  // ---- BHT 更新端口：EX 阶段解析分支结果后更新 BHT ----
  val bht_update = IO(new Bundle {
    val valid = Output(Bool())        // 有 B-type 分支需要更新
    val idx   = Output(UInt(6.W))     // PC[7:2] 索引
    val taken = Output(Bool())        // 实际是否跳转
  })

  val funct3 = in.bits.inst(14, 12)
  val rd = in.bits.inst(11, 7)
  val rs1 = in.bits.inst(19, 15)  // 当前指令的源寄存器 1 编号
  val rs2 = in.bits.inst(24, 20)  // 当前指令的源寄存器 2 编号

  val uType = in.bits.type_decode_together(8)
  val jal = in.bits.type_decode_together(7)
  val jalr = in.bits.type_decode_together(6)
  val bType = in.bits.type_decode_together(5)
  val lType = in.bits.type_decode_together(4)
  val iType = in.bits.type_decode_together(3)
  val sType = in.bits.type_decode_together(2)
  val rType = in.bits.type_decode_together(1)
  val other = in.bits.type_decode_together(0)

  val lui = uType && in.bits.inst(5)    // 最后一次译码
  val auipc = uType && !in.bits.inst(5)

  val iChoose30OrNot = iType && (funct3 === "b101".U)
  val rChoose30OrNot = rType && ((funct3 === "b101".U) || (funct3 === "b000".U))

  // ---- 数据旁路转发选择 MUX ----
  // 转发优先级: MEM（前 1 条指令结果）> WB（前 2 条指令结果）> 寄存器堆原始值（在 REG 模块中处理）
  // 当写回寄存器为 0 时，不参与转发（rd =/= 0 的检查保证这一点）

  // rs1 的转发：检测是否有前序指令写了 rs1 对应的寄存器
  val fwd_rs1_from_mem = fwd.mem_wen && (fwd.mem_rd =/= 0.U) && (fwd.mem_rd === rs1)
  val fwd_rs1_from_wb = fwd.wb_wen && (fwd.wb_rd =/= 0.U) && (fwd.wb_rd === rs1)
  val actual_rdata1 = PriorityMux(Seq(
    fwd_rs1_from_mem -> fwd.mem_data,       // 最近的指令优先
    fwd_rs1_from_wb  -> fwd.wb_data,        // 其次
    true.B           -> in.bits.reg_rdata1  // 无冲突用原始值
  ))

  // rs2 的转发：同理
  val fwd_rs2_from_exmem = fwd.mem_wen && (fwd.mem_rd =/= 0.U) && (fwd.mem_rd === rs2)
  val fwd_rs2_from_memwb = fwd.wb_wen && (fwd.wb_rd =/= 0.U) && (fwd.wb_rd === rs2)
  val actual_rdata2 = PriorityMux(Seq(
    fwd_rs2_from_exmem -> fwd.mem_data,       // 最近的指令优先
    fwd_rs2_from_memwb -> fwd.wb_data,        // 其次
    true.B             -> in.bits.reg_rdata2  // 无冲突用原始值
  ))

  // 使用转发后的寄存器数据作为 ALU 输入
  // B-type 需要用 rs1/rs2 做比较（SUB/SLT），不能用 PC/imm
  val aluA = MuxCase(0.U(32.W), Seq(
    (bType || jalr || lType || sType || iType || rType) -> actual_rdata1,
    (auipc || jal) -> in.bits.inst_addr
  ))
  val aluB = MuxCase(0.U(32.W), Seq(
    (bType || rType) -> actual_rdata2,  // B-type: rs2 用于比较
    (auipc || jal || jalr || lType || sType || iType) -> in.bits.imm
  ))
  val aluCtrl = MuxCase(0.U(4.W), Seq(
    bType -> Mux(funct3(2), Cat(0.U(2.W), funct3(2, 1)), 8.U(4.W)),
    iType -> Cat(iChoose30OrNot && in.bits.inst(30), funct3),
    rType -> Cat(rChoose30OrNot && in.bits.inst(30), funct3)
  ))

  val uALU = Module(new ALU)
  uALU.io.a      := aluA
  uALU.io.b      := aluB
  uALU.io.ctrl   := aluCtrl
  uALU.io.enable := !(lui || other)

  out.bits.inst_addr := in.bits.inst_addr
  out.bits.inst_funct3 := Mux(lType || sType, funct3, 0.U)
  out.bits.inst_rd := Mux(uType || jal || jalr || lType || iType || rType, rd, 0.U)
  out.bits.data := MuxCase(0.U(32.W), Seq(
    lui -> in.bits.imm,
    (auipc || lType || sType || iType || rType) -> uALU.io.result,
    (jal || jalr) -> (in.bits.inst_addr + 4.U(32.W))
  ))
  out.bits.reg_rdata2 := Mux(sType, actual_rdata2, 0.U)  // Store 的写入数据也需要存入下一级
  out.bits.type_decode_together := in.bits.type_decode_together

  // ---- 分支验证与流水线冲刷逻辑 ----
  // B-type 分支条件判定：统一公式: branch_taken = funct3(0) XOR (!orR) XOR funct3(2)
  val branch_taken = bType && (funct3(0) ^ (!uALU.io.zero) ^ funct3(2))

  // B-type 分支目标 = PC + B_imm（从指令字段重新提取）
  val b_imm_ex = Cat(Fill(20, in.bits.inst(31)), in.bits.inst(7), in.bits.inst(30, 25), in.bits.inst(11, 8), 0.U(1.W))
  val b_target_ex = in.bits.inst_addr + b_imm_ex

  // JALR 跳转目标 = (rs1 + I_imm12) & ~1（从指令字段提取 12 位立即数）
  val jalr_imm = Cat(Fill(20, in.bits.inst(31)), in.bits.inst(31, 20))
  val jalr_target_raw = actual_rdata1 + jalr_imm
  val jalr_target = Cat(jalr_target_raw(31, 1), 0.U(1.W)) // 清除最低位

  // - B-type: 比较实际分支方向与 ID 阶段预测方向，不一致则冲刷
  // - JALR: 未预测（predict_taken=false），始终需冲刷并重定向
  val b_mispredict = bType && (branch_taken =/= in.bits.predicted_taken)
  val b_correct_addr = Mux(branch_taken, b_target_ex, in.bits.inst_addr + 4.U(32.W))
  flush_io.enable := in.valid && (b_mispredict || jalr)
  flush_io.redirect_addr := Mux(jalr, jalr_target, b_correct_addr)

  // BHT 更新：每当 B-type 分支在 EX 阶段解析后，将实际结果写回 BHT
  bht_update.valid := in.valid && bType
  bht_update.idx   := in.bits.inst_addr(7, 2)  // PC[7:2] 索引 64 项
  bht_update.taken := branch_taken              // 实际是否跳转

  in.ready := out.ready
  out.valid := in.valid
}
