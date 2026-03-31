package mycpu.dataLane

import chisel3._
import mycpu.CPUConfig

// ============================================================================
// 流水线各级之间传递的数据包（Payload）定义
// ============================================================================
// 每个 Bundle 代表一级到下一级流水线寄存器中存储的字段
// 字段从前到后逐级携带，保证后续阶段能获取到所需信息

// ---------- Fetch → FetchBuffer(in) ----------
// 4 路取指的结果打包，包含 4 条指令及其元数据
class FetchPacket extends Bundle {
  val insts     = Vec(4, UInt(32.W))    // 4 条 32 位指令
  val pcs       = Vec(4, UInt(32.W))    // 每条指令对应的 PC 地址
  val validMask = Vec(4, Bool())        // 有效掩码（PC 未对齐时，前面的槽位无效）
}

// ---------- FetchBuffer(out) ----------
class FetchBufferEntry extends Bundle {
  val inst           = UInt(32.W)       // 32 位指令
  val pc             = UInt(32.W)       // 指令 PC
}

// ---------- Decode → Dispatch ----------
// 译码后的指令信息，包含操作类型、立即数、寄存器数据等
class Decode_Dispatch_Payload extends Bundle {
  val pc                   = UInt(32.W)  // 指令 PC
  val inst                 = UInt(32.W)  // 原始指令（后续阶段可能用到某些字段）
  val imm                  = UInt(32.W)  // 符号扩展后的立即数
  val type_decode_together = UInt(9.W)   // 指令类型独热编码：{U, JAL, JALR, B, L, I, S, R, Other}
  val predict_taken        = Bool()      // 分支预测结果
  val predict_target       = UInt(32.W)  // 预测目标
  val bht_meta             = UInt(2.W)   // BHT 状态快照
}

// ---------- Dispatch → Execute ----------
// 含 ROB 索引，准备进入执行阶段
class Dispatch_Execute_Payload extends Bundle {
  val pc                   = UInt(32.W)
  val inst                 = UInt(32.W)
  val robIdx               = UInt(CPUConfig.robIdxWidth.W) // 分配到的 ROB 表项索引
  val src1Data             = UInt(32.W)  // Dispatch 阶段读到的 rs1 数据（转发链兜底值）
  val src2Data             = UInt(32.W)  // Dispatch 阶段读到的 rs2 数据（转发链兜底值）
  val imm                  = UInt(32.W)  // 立即数
  val type_decode_together = UInt(9.W)   // 指令类型独热编码
  val predict_taken        = Bool()
  val predict_target       = UInt(32.W)
  val bht_meta             = UInt(2.W)
  val regWriteEnable       = Bool()      // 该指令是否需要写回寄存器
}

// ---------- Execute → MEM ----------
// 执行结果 + 分支验证信息
class Execute_MEM_Payload extends Bundle{
  val pc                   = UInt(32.W)
  val inst_funct3          = UInt(3.W)   // Load/Store 的 funct3（用于确定字节/半字/全字）
  val inst_rd              = UInt(5.W)   // 目标寄存器
  val data                 = UInt(32.W)  // ALU 计算结果 / 地址 / 链接地址等
  val reg_rdata2           = UInt(32.W)  // Store 的写入数据（rs2 的值）
  val type_decode_together = UInt(9.W)
  val robIdx               = UInt(CPUConfig.robIdxWidth.W)
  val regWriteEnable       = Bool()
  val isBranch             = Bool()      // 是否为分支指令
  val isJump               = Bool()      // 是否为 JAL/JALR
  val predict_taken        = Bool()      // Decode 阶段的预测
  val predict_target       = UInt(32.W)
  val actual_taken         = Bool()      // Execute 阶段计算出的实际结果
  val actual_target        = UInt(32.W)  // 实际跳转目标
  val mispredict           = Bool()      // 是否预测错误
  val bht_meta             = UInt(2.W)
}

// ---------- MEM → WB ----------
// 访存结果 + Store 信息（Store 延迟到 Commit 阶段写入 DRAM）
class MEM_WB_Payload extends Bundle{
  val pc                   = UInt(32.W)
  val inst_rd              = UInt(5.W)
  val data                 = UInt(32.W)  // 最终数据（ALU 结果或 Load 读回的值）
  val type_decode_together = UInt(9.W)
  val robIdx               = UInt(CPUConfig.robIdxWidth.W)
  val regWriteEnable       = Bool()
  val isBranch             = Bool()
  val isJump               = Bool()
  val predict_taken        = Bool()
  val predict_target       = UInt(32.W)
  val actual_taken         = Bool()
  val actual_target        = UInt(32.W)
  val mispredict           = Bool()
  val isStore              = Bool()       // 是否为 Store 指令
  val store_addr           = UInt(32.W)   // Store 地址
  val store_data           = UInt(32.W)   // Store 数据
  val store_mask           = UInt(3.W)    // Store 宽度掩码
  val bht_meta             = UInt(2.W)
}

// ============================================================================
// ROB 模块对外接口 Bundle 定义
// ============================================================================
// 将 ROB 的三大接口（分配 / 写回 / 提交）打包为独立 Bundle，使用 <> 批量连接，减少顶层接线。

// ---- ROB ↔ Dispatch 分配接口 ----
class ROBAllocIO extends Bundle {
  val valid          = Output(Bool())      // 请求分配
  val pc             = Output(UInt(32.W))  // 指令 PC
  val inst           = Output(UInt(32.W))  // 原始指令
  val rd             = Output(UInt(5.W))   // 目标寄存器
  val regWen         = Output(Bool())      // 是否写寄存器
  val isLoad         = Output(Bool())      // 是否 Load 指令
  val isStore        = Output(Bool())      // 是否 Store 指令
  val isBranch       = Output(Bool())      // 是否分支指令
  val isJump         = Output(Bool())      // 是否 JAL/JALR
  val predictTaken   = Output(Bool())      // 分支预测结果
  val predictTarget  = Output(UInt(32.W))  // 预测跳转目标
  val bhtMeta        = Output(UInt(2.W))   // BHT 状态快照
  val ready          = Input(Bool())       // ROB 是否有空位（ROB → Dispatch）
  val idx            = Input(UInt(CPUConfig.robIdxWidth.W))  // 分配到的 ROB 索引（ROB → Dispatch）
}

// ---- ROB ↔ WB 完成标记接口 ----
// 方向以 WB（发送方）为基准，全部 Output
// WB 将执行结果写入 ROB，标记对应表项为 done
class ROBWbIO extends Bundle {
  val valid          = Output(Bool())                          // 本周期有指令完成
  val idx            = Output(UInt(CPUConfig.robIdxWidth.W))   // 完成的 ROB 索引
  val result         = Output(UInt(32.W))                      // 计算结果
  val actualTaken    = Output(Bool())                          // 分支实际是否跳转
  val actualTarget   = Output(UInt(32.W))                      // 实际跳转目标
  val mispredict     = Output(Bool())                          // 是否预测错误
  val storeAddr      = Output(UInt(32.W))                      // Store 地址
  val storeData      = Output(UInt(32.W))                      // Store 数据
  val storeMask      = Output(UInt(3.W))                       // Store 宽度掩码
}

// ---- ROB 提交接口 ----
// 方向以 ROB（发送方）为基准，全部 Output
// Commit 阶段从 ROB 头部取出已完成的指令，驱动 RegFile 写回和 DRAM Store
class ROBCommitIO extends Bundle {
  val valid          = Output(Bool())      // 本周期是否有指令提交
  val pc             = Output(UInt(32.W))  // 提交指令的 PC
  val inst           = Output(UInt(32.W))  // 原始指令
  val rd             = Output(UInt(5.W))   // 目标寄存器
  val regWen         = Output(Bool())      // 是否写寄存器
  val result         = Output(UInt(32.W))  // 计算结果
  val isStore        = Output(Bool())      // 是否 Store
  val storeAddr      = Output(UInt(32.W))  // Store 地址
  val storeData      = Output(UInt(32.W))  // Store 数据
  val storeMask      = Output(UInt(3.W))   // Store 宽度掩码
  val isBranch       = Output(Bool())      // 是否分支指令
  val isJump         = Output(Bool())      // 是否 JAL/JALR
  val mispredict     = Output(Bool())      // 是否预测错误
  val actualTaken    = Output(Bool())      // 分支实际结果
  val actualTarget   = Output(UInt(32.W))  // 实际跳转目标
  val predictTaken   = Output(Bool())      // 预测结果
  val bhtMeta        = Output(UInt(2.W))   // BHT 状态快照
}

// ---- ROB Flush 接口 ----
// Commit 阶段发现预测错误时触发，重定向 PC 到正确地址
class ROBFlushIO extends Bundle {
  val valid = Output(Bool())      // 需要 flush
  val addr  = Output(UInt(32.W))  // 正确的跳转目标地址
}
