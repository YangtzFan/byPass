package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// Fetch（取指阶段）—— 4 路宽取指 + BPU 分支预测
// ============================================================================
// 每周期从 IROM 读取 128 位数据（4 条 32 位指令），进行轻量预译码，
// 使用 BHT 完成分支预测，然后打包成 FetchPacket 输出到 FetchBuffer。
//
// BPU 预测规则：
//   - JAL：始终预测跳转，目标 = PC + J-type 立即数
//   - B-type：使用 BHT 预测结果，目标 = PC + B-type 立即数
//   - JALR：预测不跳转（目标依赖 rs1，Fetch 无法确定）
//   - 在 4 条指令中找到第一条预测跳转的分支，截断后续槽位的 validMask
//
// 地址对齐处理：PC[3:2] 决定起始槽位，只有 >= start_slot 的槽位有效
// ============================================================================
class Fetch extends Module {
  private val useBHT = CPUConfig.useBHT
  private val bhtIdxWidth = log2Ceil(CPUConfig.bhtEntries)

  val in = IO(Flipped(Decoupled(UInt(32.W)))) // 输入：PC 值
  val out = IO(Decoupled(new FetchBufferPacket))  // 输出：取指结果包（含预测信息）

  // ---- 读 IROM 接口 ----
  val rom = IO(new Bundle {
    val inst_addr_o = Output(UInt(14.W))      // 输出到 IROM 的地址（128-bit 字地址 = PC[17:4]）
    val inst_i      = Input(UInt(128.W))      // IROM 返回的 128 位数据（4 条指令拼接）
  })

  // ---- BHT 接口（由 myCPU 顶层连接到 BHT 模块）----
  val bht = Option.when(useBHT){IO(new Bundle {
    val read_idx = Output(Vec(4, UInt(bhtIdxWidth.W)))  // 4 路 BHT 读索引
    val predict  = Input(Vec(4, UInt(2.W)))              // 4 路 BHT 预测值（2-bit 格雷码）
  })}

  // ---- 预测结果输出（送给 PC 模块进行重定向）----
  val predict = IO(new Bundle {
    val jump   = Output(Bool())         // 是否有预测跳转
    val target = Output(UInt(32.W))     // 预测跳转目标
  })

  val pc = in.bits            // 当前 PC 值
  val basePC = pc(31, 4) ## 0.U(4.W) // 这是 16 字节对齐的基址 PC
  val startSlot = pc(3, 2)    // 在 4 条指令中的起始槽位索引（0~3）

  // ---- 取出 4 条指令并求对应 PC 值 ----
  rom.inst_addr_o := pc(17, 4) // IROM 地址：128-bit 字索引 = PC[17:4]，14 位宽
  val insts = Wire(Vec(4, UInt(32.W)))
  for (i <- 0 until 4) {      // 将 128 位数据拆分为 4 × 32 位指令
    insts(i) := rom.inst_i(32 * i + 31, 32 * i)
  }
  val pcs = Wire(Vec(4, UInt(32.W)))
  for (i <- 0 until 4) {      // 求每条指令的 PC 值（即使非对齐，也会取相应对齐地址向后 4 条指令）
    pcs(i) := (basePC(31, 2) + i.U) ## 0.U(2.W)
  }

  // ---- 轻量预译码 + BPU 预测 ----
  val slotTaken   = Wire(Vec(4, Bool()))       // 每个槽位是否预测跳转
  val slotTarget  = Wire(Vec(4, UInt(32.W)))   // 每个槽位的预测目标
  val slotBHTMeta = Wire(Vec(4, UInt(2.W)))    // 每个槽位的 BHT 元数据
  val validTaken  = Wire(Vec(4, Bool()))

  for (i <- 0 until 4) { // 开始遍历每一条指令
    val inst   = insts(i)
    val opcode = inst(6, 0)

    // 指令类型识别（仅关注条件分支和JAL指令，JALR 预测为不跳转，无需专门处理）
    val isJal   = opcode === "b1101111".U
    val isBtype = opcode === "b1100011".U
    // J-type 立即数提取（JAL 目标 = PC + J-imm）
    val jalTarget = pcs(i) + Cat(Fill(12, inst(31)), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))
    // B-type 立即数提取（B 目标 = PC + B-imm）
    val bTarget = pcs(i) + Cat(Fill(20, inst(31)), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))

    if (useBHT) { // BHT 查询
      bht.get.read_idx(i) := pcs(i)(bhtIdxWidth + 1, 2)  // 用 PC 低位索引 BHT
    }
    val predictTaken = if (useBHT) { // BHT 预测：格雷码高位 bit[1] = 1 表示预测跳转
      isBtype && bht.get.predict(i)(1)
    } else { // 静态 BTFN 策略：B-type 立即数为负（向后跳）时预测跳转
      isBtype && inst(31)
    }

    // 输出每个槽位的结果
    slotTaken(i)   := isJal || predictTaken
    slotTarget(i)  := Mux(slotTaken(i), Mux(isJal, jalTarget, bTarget), 0.U(32.W))
    slotBHTMeta(i) := { if (useBHT){bht.get.predict(i)} else {0.U(2.W)} }  // BHT 计数器原始值
    validTaken(i)  := (i.U >= startSlot) && slotTaken(i) // 每个槽位是否为"有效且预测跳转"
  }

  val hasTaken = validTaken.asUInt.orR // 是否存在至少一条预测跳转的指令
  val firstTakenSlot = PriorityEncoder(validTaken.asUInt) // 第一条预测跳转的槽位索引

  // ---- 预测结果输出（给 PC 模块在 myCPU 中使用）----
  predict.jump   := hasTaken && out.fire
  predict.target := slotTarget(firstTakenSlot)
  
  // ---- 输出 FetchPacket ----
  for (i <- 0 until 4) {
    out.bits.entries(i).inst := insts(i)
    out.bits.entries(i).pc   := pcs(i)

    // validMask 逻辑：
    //   1. 槽位索引 >= startSlot（PC 对齐要求）
    //   2. 如果存在预测跳转，则仅保留第一条跳转及之前的槽位，防止不相关指令进入 FetchBuffer
    out.bits.valid(i) := (i.U >= startSlot) && (!hasTaken || i.U <= firstTakenSlot)

    // 预测信息按槽位输出（仅有效槽位的预测有意义）
    out.bits.entries(i).predict_taken  := slotTaken(i)
    out.bits.entries(i).predict_target := slotTarget(i)
    out.bits.entries(i).bht_meta       := slotBHTMeta(i)
  }

  in.ready  := out.ready // 反压：下游不接收时，PC 也暂停
  out.valid := in.valid  // PC 有效时，取指结果就有效
}
