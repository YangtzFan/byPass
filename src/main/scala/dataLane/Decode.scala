package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig
import mycpu.device.SE
import mycpu.device.BHT

// ============================================================================
// Decode（译码阶段）—— 宽度 1
// ============================================================================
// 从 FetchBuffer 取出 1 条指令，解码出操作类型、立即数、寄存器地址等
// 同时在此阶段完成 **分支预测**（JAL 直接预测跳转，B 型根据 BHT 或静态策略预测）
// ============================================================================
class Decode extends Module {
  val bht_entries = CPUConfig.bhtEntries
  val bht_use = CPUConfig.useBHT

  val in = IO(Flipped(Decoupled(new FetchBufferEntry)))  // 来自 FetchBuffer 的单条指令
  val out = IO(Decoupled(new Decode_Dispatch_Payload))   // 输出译码结果
  // ---- 分支跳转预测接口 ----
  val bpu = IO(new Bundle {
    val flush = Input(Bool())               // Commit 阶段 flush 信号（预测错误时清空流水线）
    val predict_jump = Output(Bool())       // Decode 预测跳转使能（送给 PC 模块）
    val predict_target = Output(UInt(32.W)) // Decode 预测跳转目标地址
    val update_valid = Option.when(bht_use)(Input(Bool()))            // Execute 是否更新
    val update_idx = Option.when(bht_use)(Input(UInt(bht_entries.W))) // Execute 更新表项
    val update_taken = Option.when(bht_use)(Input(Bool()))            // Execute 预测结果（是否跳转）
  })

  // ---- Load-Use 冒险检测接口 ----
  // 检测当前 Decode 的指令是否依赖尚未完成的 Load 结果。若检测到冒险，Decode 自行停顿一拍。
  val hazard = IO(new Bundle {
    val ex_rd     = Input(UInt(5.W))    // Dispatch→Execute 阶段指令的目标寄存器编号
    val ex_isLoad = Input(Bool())       // 该指令是否为 Load 指令
    val ex_valid  = Input(Bool())       // 该流水级是否有有效指令
    val stall     = Output(Bool())      // Load-Use 停顿信号输出（供上层模块观测）
  })

  // ---- 指令字段拆解（RISC-V 标准格式）----
  val opcode = in.bits.inst(6, 0)    // 操作码
  val rd = in.bits.inst(11, 7)       // 目标寄存器
  val funct3 = in.bits.inst(14, 12)  // 功能码 3
  val rs1 = in.bits.inst(19, 15)     // 源寄存器 1
  val rs2 = in.bits.inst(24, 20)     // 源寄存器 2
  val funct7 = in.bits.inst(31, 25)  // 功能码 7
  val imm12 = Cat(funct7, rs2)       // 12 位立即数原始字段
  val imm20 = Cat(funct7, rs2, rs1, funct3) // 20 位立即数原始字段

  // ---- 指令类型识别 ----
  val uType = (opcode === "b0110111".U) || (opcode === "b0010111".U) // LUI / AUIPC
  val jal = opcode === "b1101111".U    // JAL（无条件跳转）
  val jalr = opcode === "b1100111".U   // JALR（寄存器间接跳转）
  val bType = opcode === "b1100011".U  // B 型分支指令（BEQ/BNE/BLT 等）
  val lType = opcode === "b0000011".U  // Load 指令（LB/LH/LW 等）
  val iType = opcode === "b0010011".U  // I 型算术/逻辑指令（ADDI/ANDI 等）
  val sType = opcode === "b0100011".U  // Store 指令（SB/SH/SW）
  val rType = opcode === "b0110011".U  // R 型算术/逻辑指令（ADD/AND 等）
  val other = (opcode === "b0001111".U) || (opcode === "b1110011".U) // FENCE / ECALL 等

  // 将指令类型拼成 9 位独热编码，后续阶段用单比特判断即可
  // 顺序从高到低：{uType, jal, jalr, bType, lType, iType, sType, rType, other}
  val type_decode_together = Cat(uType, jal, jalr, bType, lType, iType, sType, rType, other)

  // ---- 立即数生成器（符号扩展）----
  val uSE = Module(new SE)
  uSE.io.type_decode_together := type_decode_together
  uSE.io.imm12 := imm12
  uSE.io.imm20 := imm20
  uSE.io.funct7 := funct7
  uSE.io.rd := rd

  // ---- 输出译码结果 ----
  out.bits.pc := in.bits.pc
  out.bits.inst := in.bits.inst
  out.bits.imm := uSE.io.imm_o               // 符号扩展后的立即数
  out.bits.type_decode_together := type_decode_together

  // ---- 分支预测（根据 CPUConfig 选择性生成）----
  // JAL：目标地址 = PC + 立即数偏移，始终预测跳转
  // B 型：目标地址 = PC + 立即数偏移，预测取决于 BHT 或静态策略
  // JALR：目标依赖 rs1，Decode 阶段无法确定，延迟到 Execute 阶段处理
  val jal_target = in.bits.pc + uSE.io.imm_o
  val b_target = in.bits.pc + uSE.io.imm_o

  // ---- BHT（分支历史表，可选模块）----
  // 用 2-bit 饱和计数器做动态分支预测，可切换为静态预测。用当前 PC 的低位索引 BHT，读取预测结果
  val uBHT = Option.when(bht_use)(Module(new BHT(bht_entries)))
  if (bht_use) {
    uBHT.get.io.read_idx := in.bits.pc(log2Ceil(bht_entries) + 1, 2)
    uBHT.get.io.update_valid := bpu.update_valid.get && bpu.flush // BHT 更新：Execute 阶段得到分支实际结果后，回写 BHT
    uBHT.get.io.update_idx   := bpu.update_idx.get
    uBHT.get.io.update_taken := bpu.update_taken.get
  }
  
  val b_predict_taken = if (bht_use) {
    bType && uBHT.get.io.predict_taken(1) // 动态预测：查 BHT 表
  } else {
    bType && uSE.io.imm_o(31)             // 静态预测：偏移为负（向后跳）则预测跳转
  }
  val predict_taken = jal || b_predict_taken
  val predict_target = Mux(jal, jal_target, b_target)
  bpu.predict_jump := predict_taken
  bpu.predict_target := predict_target
  out.bits.predict_taken := predict_taken
  out.bits.predict_target := predict_target
  out.bits.bht_meta := 0.U             // BHT 元数据（当前未使用，留给后续扩展）

  // ---- Load-Use 冒险检测逻辑 ----
  // 原理：当 Dispatch → Execute 流水线寄存器中的指令是 Load 时，其结果要到 MEM 阶段
  // 才能从 DRAM 读回（比 ALU 类指令晚一拍可用）。如果当前 Decode 的指令的
  // 源寄存器（rs1 或 rs2）恰好依赖该 Load 的目标寄存器（rd），则必须暂停
  // Decode 一个周期，等 Load 数据在 MEM 阶段完成后，通过 MEM → Execute 旁路转发给出。
  val use_rs1 = jalr || bType || iType || sType || rType || lType  // 这些指令需要读 rs1
  val use_rs2 = bType || sType || rType                            // 这些指令需要读 rs2
  val loadUseStall = hazard.ex_valid && hazard.ex_isLoad && (hazard.ex_rd =/= 0.U) &&
    ((use_rs1 && (rs1 === hazard.ex_rd)) ||
     (use_rs2 && (rs2 === hazard.ex_rd)))
  hazard.stall := loadUseStall

  // ---- 握手信号（含 Load-Use 停顿和 Flush 控制）----
  // 当检测到 Load-Use 冒险时：
  //   - in.ready 拉低：不从 FetchBuffer 消费新指令，保持当前指令不变
  //   - out.valid 拉低：不向 Dispatch 输出译码结果，插入一个气泡（bubble）
  // 当 Commit 阶段发现预测错误触发 flush 时：
  //   - out.valid 拉低：防止错误路径上的指令进入下游流水级
  //   - in.ready 不需要额外门控，因为 FetchBuffer 已在同周期被 flush 清空
  // 停顿一拍后，Load 结果在 MEM 阶段就绪，可通过旁路转发解决数据依赖
  in.ready := out.ready && !loadUseStall
  out.valid := in.valid && !loadUseStall && !bpu.flush
}
