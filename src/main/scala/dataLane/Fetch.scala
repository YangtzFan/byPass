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
//   - JALR：若 useBTB 开启并命中，预测跳转到 BTB 缓存的目标；否则预测不跳转。
//   - 在 4 条指令中找到第一条预测跳转的分支，截断后续槽位的 valid
//
// 地址对齐处理：PC[3:2] 决定起始槽位，只有 >= start_slot 的槽位有效
// ============================================================================
class Fetch extends Module {
  private val useBHT = CPUConfig.useBHT
  private val useBTB = CPUConfig.useBTB
  private val bhtIdxWidth = log2Ceil(CPUConfig.bhtEntries)

  val in = IO(Flipped(Decoupled(UInt(32.W)))) // 输入：PC 值
  val out = IO(Decoupled(Vec(CPUConfig.fetchWidth, new FetchBufferEntry))) // 输出：4 个取指结果包（含预测信息）

  // ---- 取指存储访问接口（接 I-Cache 或 IROM 旁路延时队列）----
  // reqAddr  : 14-bit 128-bit-word 地址（= PC[17:4]），Decoupled 发起取指请求
  // respData : 128-bit 数据响应（4 条指令拼接），Flipped Decoupled
  // 单 outstanding 设计：任意时刻最多 1 个在飞请求；通过 inflight/discard 两个寄存器
  // 实现 flush 时丢弃在飞响应，避免错误数据进入 FetchBuffer。
  val mem = IO(new Bundle {
    val reqAddr  = Decoupled(UInt(14.W))
    val respData = Flipped(Decoupled(UInt(128.W)))
  })

  // flush 输入：连接 memRedirectValid。拉高时：
  //   1) 阻止本拍发起新请求；2) 若有在飞请求且响应未到，标记 discard。
  val flush = IO(Input(Bool()))

  // ---- BHT 接口（由 myCPU 顶层连接到 BHT 模块）----
  val bht = Option.when(useBHT){IO(new Bundle {
    val read_idx = Output(Vec(CPUConfig.fetchWidth, UInt(bhtIdxWidth.W)))  // 4 路 BHT 读索引
    val predict  = Input(Vec(CPUConfig.fetchWidth, UInt(2.W)))             // 4 路 BHT 预测值（2-bit 格雷码）
  })}

  // ---- BTB 接口（仅 useBTB 开启时存在）----
  // 为 JALR 的间接跳转目标提供缓存预测。
  val btb = Option.when(useBTB){IO(new Bundle {
    val read_pc = Output(Vec(CPUConfig.fetchWidth, UInt(32.W))) // 4 路并行 BTB 查询 PC
    val hit     = Input(Vec(CPUConfig.fetchWidth, Bool()))
    val target  = Input(Vec(CPUConfig.fetchWidth, UInt(32.W)))
  })}

  // ---- 预测结果输出 ----
  val predict = IO(new Bundle {
    val jump   = Output(Bool())         // 是否有预测跳转
    val target = Output(UInt(32.W))     // 预测跳转目标
  })

  val pc = in.bits         // 当前 PC 值
  val basePC = pc(31, 4) ## 0.U(4.W) // 这是 16 字节对齐的基址 PC
  val startSlot = pc(3, 2) // 在 4 条指令中的起始槽位索引（0~3）

  // ============================================================================
  // 单 MSHR 状态机
  // ----------------------------------------------------------------------------
  // inflight : 是否已发出取指请求但响应未到（含已到本拍但未消费的情况，由 respFire 本拍清零）
  // discard  : 当前 inflight 的请求由于 flush 已经作废，下一次到达的响应直接丢弃
  // 不变量：inflight ⇒ 唯一一个 outstanding；canIssue 要求 !inflight && !flush。
  // ============================================================================
  val inflight = RegInit(false.B)
  val discard  = RegInit(false.B)

  // ---- 发起请求 ----
  val canIssue = in.valid && !inflight && !flush
  mem.reqAddr.valid := canIssue
  mem.reqAddr.bits  := pc(17, 4)

  // ---- 响应吸收 / 正常消费 ----
  // 当处于 discard 状态、或本拍 flush，且响应到达：无条件吸收并丢弃。
  val absorbDiscard = mem.respData.valid && (discard || flush)
  // 正常路径：响应有效、未被丢弃、未 flush，本拍可用于 BPU 计算与下游交付。
  val respUsable    = mem.respData.valid && !discard && !flush
  val instData128   = mem.respData.bits

  // respData.ready 由两条路径合成：吸收丢弃 或 正常路径下游接收
  mem.respData.ready := absorbDiscard || (respUsable && out.ready)
  val respFire = mem.respData.fire

  // 请求成功发出时置 inflight
  when(mem.reqAddr.fire) {
    inflight := true.B
  }
  // 响应完成时（无论是被丢弃还是正常消费）清零 inflight / discard
  // 注意：放在 reqAddr.fire 之后，使用最后连接语义保证“同拍 req+resp（0 拍访存延迟工况）”
  // 时 inflight 最终为 false，避免单 MSHR 在 0 延迟模型下卡死。
  when(respFire) {
    inflight := false.B
    discard  := false.B
  }
  // flush 时若 inflight 但响应尚未到达本拍，标记 discard 等待后续响应消化
  when(flush && inflight && !mem.respData.valid) {
    discard := true.B
  }

  // ---- 解包 4 条指令并求对应 PC 值 ----
  val pcs = Wire(Vec(CPUConfig.fetchWidth, UInt(32.W)))
  val insts = Wire(Vec(CPUConfig.fetchWidth, UInt(32.W)))
  for (i <- 0 until CPUConfig.fetchWidth) {
    pcs(i) := (basePC(31, 2) + i.U) ## 0.U(2.W) // 求每条指令的 PC 值（即使非对齐，也会取相应对齐地址向后 4 条指令）
    insts(i) := instData128(32 * i + 31, 32 * i) // 将 128 位数据解包为 4 × 32 位指令
  }

  // ---- 轻量预译码 + BPU 预测 ----
  val slotTaken   = Wire(Vec(CPUConfig.fetchWidth, Bool()))     // 每个槽位是否预测跳转
  val slotTarget  = Wire(Vec(CPUConfig.fetchWidth, UInt(32.W))) // 每个槽位的预测目标
  val slotBHTMeta = Wire(Vec(CPUConfig.fetchWidth, UInt(2.W)))  // 每个槽位的 BHT 元数据
  val validTaken  = Wire(Vec(CPUConfig.fetchWidth, Bool()))
  for (i <- 0 until CPUConfig.fetchWidth) { // 开始遍历每一条指令
    val inst   = insts(i)
    val opcode = inst(6, 0)

    // 指令类型识别（仅关注条件分支和JAL指令，JALR 预测由 BTB 提供）
    val isJal   = opcode === "b1101111".U
    val isBtype = opcode === "b1100011".U
    val isJalr  = opcode === "b1100111".U
    val jalTarget = pcs(i) + Cat(Fill(12, inst(31)), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W)) // JAL 目标
    val bTarget = pcs(i) + Cat(Fill(20, inst(31)), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)) // B-type 目标
    // 分支跳转预测结果
    val predictTaken = if (useBHT) { // BHT 预测：格雷码高位 bit[1] = 1 表示预测跳转
      bht.get.read_idx(i) := pcs(i)(bhtIdxWidth + 1, 2)  // 用 PC 低位索引 BHT
      isBtype && bht.get.predict(i)(1)
    } else { // 静态 BTFN 策略：B-type 立即数为负（向后跳）时预测跳转
      isBtype && inst(31)
    }

    // ---- BTB 查询：给 JALR 一个预测目标 ----
    // 命中 && 是 JALR 时，把该槽预测为 taken，目标取 BTB 缓存；
    // 未命中或非 JALR 时 btb 查询结果忽略（不影响 B/JAL/普通指令）。
    val btbHit    = if (useBTB) { btb.get.read_pc(i) := pcs(i); btb.get.hit(i) } else { false.B }
    val btbTarget = if (useBTB) { btb.get.target(i) } else { 0.U(32.W) }
    val jalrPredictTaken = isJalr && btbHit

    // 输出每个槽位的结果
    slotTaken(i)   := isJal || predictTaken || jalrPredictTaken // JAL 一定跳转；B 按 BHT；JALR 按 BTB
    slotTarget(i)  := Mux(slotTaken(i),
                        Mux(isJal, jalTarget,
                          Mux(jalrPredictTaken, btbTarget, bTarget)),
                        0.U(32.W))
    slotBHTMeta(i) := { if (useBHT){bht.get.predict(i)} else {0.U(2.W)} }  // BHT 计数器原始值
    validTaken(i)  := (i.U >= startSlot) && slotTaken(i) // 每个槽位是否为"有效且预测跳转"
  }
  
  // ---- 预测结果输出（给 PC 模块在 myCPU 中使用）----
  val hasTaken = validTaken.asUInt.orR // 是否存在至少一条预测跳转的指令
  val firstTakenSlot = PriorityEncoder(validTaken.asUInt) // 第一条预测跳转的槽位索引
  predict.jump   := hasTaken && out.fire // 下游可以接收数据时才执行分支跳转预测
  predict.target := slotTarget(firstTakenSlot)
  
  // ---- 输出 FetchPacket ----
  for (i <- 0 until CPUConfig.fetchWidth) {
    out.bits(i).pc   := pcs(i)
    out.bits(i).inst := insts(i)
    out.bits(i).predict_taken  := slotTaken(i)
    out.bits(i).predict_target := slotTarget(i)
    out.bits(i).bht_meta       := slotBHTMeta(i)
    // 预测信息按槽位输出。valid 逻辑：
    //   1. 槽位索引 >= startSlot（PC 对齐要求）
    //   2. 如果存在预测跳转，则仅保留第一条预测为跳转及之前的槽位，防止不相关指令进入 FetchBuffer
    out.bits(i).valid := (i.U >= startSlot) && (!hasTaken || i.U <= firstTakenSlot)
  }

  in.ready  := respUsable && out.ready // 仅当响应已到且下游可收，PC 才前进
  out.valid := in.valid && respUsable  // PC 有效且响应可用时输出
}
