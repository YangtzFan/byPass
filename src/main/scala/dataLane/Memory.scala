package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// Memory（访存阶段，原 MEM）+ 分支纠错重定向 + StoreBuffer 转发
// ============================================================================
// - Load 指令：
//     1. 先检查 StoreBuffer 中是否有更老的 Store 命中同一地址
//        a) 如果有且地址已知 → 从 StoreBuffer 转发数据
//        b) 如果有更老的 Store 但地址未知 → 流水线停顿（等待 Store 地址计算完成）
//        c) 没有匹配 → 从 DRAM 读取数据
//     2. 最终输出 Load 读回的数据
// - Store 指令：**不在此阶段写入** DRAM，Store 数据已在 Execute 阶段写入 StoreBuffer，
//   延迟到 Commit 阶段才实际写入内存
// - 其他指令：直接透传 Execute 阶段的结果
//
// 分支纠错重定向：
//   - Execute 在上一阶段计算分支真值并标记 mispredict
//   - Memory 阶段读取 mispredict 标志，输出重定向信号到 PC 模块
//   - 同时冲刷 Memory 级前面所有流水寄存器，回退 ROB 指针
//   - PC 优先级：Memory redirect > Fetch predict > sequential
// ============================================================================
class Memory extends Module {
  val in  = IO(Flipped(Decoupled(new Execute_Memory_Payload)))  // 输入：来自 ExMemDff
  val out = IO(Decoupled(new Memory_Refresh_Payload))           // 输出：送往 MemRefDff → Refresh

  // ---- DRAM 读端口接口（Load 使用）----
  val io = IO(new Bundle {
    val ram_addr_o  = Output(UInt(32.W))          // DRAM 读地址
    val ram_mask_o  = Output(UInt(3.W))           // 访存宽度掩码
    val ram_rdata_i = Input(UInt(32.W))           // DRAM 读回数据
  })

  // ---- Memory 阶段重定向输出 ----
  val redirect = IO(new Bundle {
    val valid  = Output(Bool())                        // 需要重定向
    val addr   = Output(UInt(32.W))                    // 正确的跳转目标地址
    val robIdx = Output(UInt(CPUConfig.robPtrWidth.W)) // 误预测指令的 ROB 指针（用于回滚）
  })

  // ---- StoreBuffer 写入端口（将 Store 地址和数据写入 StoreBuffer）----
  val sbWrite = IO(new Bundle {
    val valid = Output(Bool())                           // 写入使能
    val idx   = Output(UInt(CPUConfig.sbPtrWidth.W))     // 要写入的 StoreBuffer 表项指针
    val addr  = Output(UInt(32.W))                       // Store 地址
    val data  = Output(UInt(32.W))                       // Store 数据
    val mask  = Output(UInt(3.W))                        // Store 宽度掩码
  })

  // ---- StoreBuffer 查询接口（Load 指令需要检查 Store-to-Load 转发）----
  val sbQuery = IO(new Bundle {
    val valid       = Output(Bool())                   // 是否进行 StoreBuffer 查询（Load 指令有效时）
    val addr        = Output(UInt(32.W))               // Load 的地址
    val hit         = Input(Bool())                    // StoreBuffer 中是否有更老的 Store 命中
    val data        = Input(UInt(32.W))                // StoreBuffer 转发的数据
  })

  // 从类型编码中提取各指令类型
  val uType = in.bits.type_decode_together(8)
  val jal   = in.bits.type_decode_together(7)
  val jalr  = in.bits.type_decode_together(6)
  val lType = in.bits.type_decode_together(4)  // Load
  val iType = in.bits.type_decode_together(3)
  val sType = in.bits.type_decode_together(2)  // Store
  val rType = in.bits.type_decode_together(1)

  // ---- StoreBuffer 写入（Store 指令）
  sbWrite.valid := in.valid && sType && in.bits.isSbAlloc
  sbWrite.idx   := in.bits.sbIdx
  sbWrite.addr  := in.bits.data                // Store 地址 = rs1 + 立即数（ALU 计算结果）
  sbWrite.data  := in.bits.reg_rdata2          // Store 数据 = rs2 的值
  sbWrite.mask  := in.bits.inst_funct3         // Store 宽度掩码 = funct3

  // ---- StoreBuffer 查询（Load 指令）----
  sbQuery.valid  := in.valid && lType
  sbQuery.addr   := in.bits.data       // Load 地址 = ALU 计算结果

  // ---- Store-to-Load 冒险检测：存在更老 Store 地址未知时停顿 ----
  val sbStall = in.valid && lType && !sbQuery.hit

  // DRAM 读端口：只有 Load 指令且不从 StoreBuffer 转发时才需要读 DRAM
  io.ram_addr_o := Mux(lType && !sbQuery.hit, in.bits.data, 0.U)
  io.ram_mask_o := Mux(lType, in.bits.inst_funct3, 0.U)

  // ---- 重定向逻辑 ----
  // 当 Execute 阶段检测到分支预测错误时，Memory 阶段发出重定向信号
  redirect.valid  := in.valid && in.bits.mispredict && !sbStall
  redirect.addr   := in.bits.actual_target
  redirect.robIdx := in.bits.robIdx

  // ---- Load 数据来源选择 ----
  // 优先级：StoreBuffer 转发 > DRAM 读取
  val loadData = Mux(sbQuery.hit, sbQuery.data, io.ram_rdata_i)

  // ---- 输出结果打包 ----
  out.bits.pc := in.bits.pc
  out.bits.inst_rd := Mux(uType || jal || jalr || lType || iType || rType, in.bits.inst_rd, 0.U)
  out.bits.data := MuxCase(0.U, Seq(
    (uType || jal || jalr || iType || rType) -> in.bits.data,  // 非访存指令：透传 ALU 结果
    lType -> loadData                                           // Load：StoreBuffer 转发或 DRAM 读回
  ))
  out.bits.type_decode_together := in.bits.type_decode_together
  out.bits.robIdx         := in.bits.robIdx
  out.bits.regWriteEnable := in.bits.regWriteEnable
  out.bits.isBranch       := in.bits.isBranch
  out.bits.isJump         := in.bits.isJump
  out.bits.predict_taken  := in.bits.predict_taken
  out.bits.predict_target := in.bits.predict_target
  out.bits.actual_taken   := in.bits.actual_taken
  out.bits.actual_target  := in.bits.actual_target
  out.bits.mispredict     := in.bits.mispredict
  out.bits.bht_meta       := in.bits.bht_meta

  // ---- 握手信号 ----
  // StoreBuffer 地址未知停顿时：不消费输入，不输出
  in.ready  := out.ready && !sbStall
  out.valid := in.valid && !sbStall
}
