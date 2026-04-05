package mycpu.dataLane

import chisel3._
import chisel3.util.Valid
import mycpu.CPUConfig

// ============================================================================
// 流水线各级之间传递的数据包（Payload）定义
// ============================================================================
// 10 级流水线：
//   Fetch(4,BPU) → [FetchBuffer(4in/4out)] → Decode(4) → [DecRenDff]
//   → Rename(4) → [RenDisDff] → Dispatch(4,ROB alloc) → [IssueQueue(4in/4out)]
//   → Issue(1,load-use) → [IssRRDff] → ReadReg(1) → [RRExDff]
//   → Execute(1,branch verify) → [ExMemDff] → Memory(1,redirect) → [MemRefDff]
//   → Refresh(1) → Commit(ROB)

// ==========================================================================
// FetchBuffer：存储于环形缓冲区中的指令信息
// ==========================================================================
class FetchBufferEntry extends Bundle {
  val inst           = UInt(32.W) // 32 位指令
  val pc             = UInt(32.W) // 指令 PC
  val predict_taken  = Bool()     // Fetch 阶段 BPU 预测结果
  val predict_target = UInt(32.W) // 预测跳转目标
  val bht_meta       = UInt(2.W)  // BHT 状态快照
}

// ==========================================================================
// Fetch → FetchBuffer(in/out)：4 路取指结果打包
// ==========================================================================
class FetchBufferPacket extends Bundle {
  val entries = Vec(4, new FetchBufferEntry)
  val valid   = Vec(4, Bool()) // 有效指示位
}

// ==========================================================================
// DecodedInst：单条译码结果（4-wide Decode 的每路输出）
// ==========================================================================
class DecodedInst extends Bundle {
  val pc                   = UInt(32.W)       // 指令 PC
  val inst                 = UInt(32.W)       // 原始指令
  val imm                  = UInt(32.W)       // 符号扩展后的立即数
  val type_decode_together = UInt(9.W)        // 指令类型独热编码 {U,JAL,JALR,B,L,I,S,R,Other}
  val predict_taken        = Bool()           // Fetch 阶段分支预测结果
  val predict_target       = UInt(32.W)       // 预测跳转目标
  val bht_meta             = UInt(2.W)        // BHT 状态快照
}

// ==========================================================================
// Decode_Rename_Payload：4-wide 译码结果（Decode → Rename）
// ==========================================================================
class Decode_Rename_Payload extends Bundle {
  val insts = Vec(4, new DecodedInst) // 4 条译码结果
  val valid = Vec(4, Bool())          // 有效指示位
}

// ==========================================================================
// RenameEntry：经过 Rename 阶段后的单条指令信息
// ==========================================================================
// 注意：当前版本 Rename 是纯打拍占位级，不分配 ROB，不进行寄存器重命名。
// regWriteEnable 在 Rename 阶段从 type_decode_together 计算。
// ==========================================================================
class RenameEntry extends Bundle {
  val pc                   = UInt(32.W)
  val inst                 = UInt(32.W)
  val imm                  = UInt(32.W)
  val type_decode_together = UInt(9.W)
  val predict_taken        = Bool()
  val predict_target       = UInt(32.W)
  val bht_meta             = UInt(2.W)
  val regWriteEnable       = Bool()           // 是否需要写回寄存器（Rename 阶段计算）
}

// ==========================================================================
// Rename_Dispatch_Payload：4-wide Rename 输出包（Rename → RenDisDff → Dispatch）
// ==========================================================================
class Rename_Dispatch_Payload extends Bundle {
  val entries = Vec(4, new RenameEntry)
  val valid   = Vec(4, Bool())
  val count   = UInt(3.W) // 有效指令数量（0-4），用于 Dispatch 派遣计数
}

// ==========================================================================
// DispatchEntry：经过 Dispatch 阶段后的单条指令信息（含 ROB 索引）
// ==========================================================================
// Dispatch 阶段负责分配 ROB 表项、StoreBuffer 表项
// ==========================================================================
class DispatchEntry extends Bundle {
  val pc                   = UInt(32.W)
  val inst                 = UInt(32.W)
  val imm                  = UInt(32.W)
  val type_decode_together = UInt(9.W)
  val predict_taken        = Bool()
  val predict_target       = UInt(32.W)
  val bht_meta             = UInt(2.W)
  val robIdx               = UInt(CPUConfig.robPtrWidth.W)       // 分配到的 ROB 指针（含回绕位）
  val regWriteEnable       = Bool()                              // 是否需要写回寄存器
  val sbIdx                = UInt(CPUConfig.sbPtrWidth.W)        // 分配到的 StoreBuffer 指针（仅 Store 有效）
  val isSbAlloc            = Bool()                              // 是否在 StoreBuffer 中分配了表项
}

// ==========================================================================
// DispatchPacket：4-wide Dispatch 输出包（Dispatch → IssueQueue）
// ==========================================================================
class DispatchPacket extends Bundle {
  val entries = Vec(4, new DispatchEntry)
  val valid   = Vec(4, Bool())
  val count   = UInt(3.W) // 本次实际派遣的有效指令数量（0-4）
}

// ==========================================================================
// Issue_ReadReg_Payload：Issue → ReadReg 流水线数据
// ==========================================================================
// Issue 阶段从 IssueQueue 取出 1 条指令，传给 ReadReg 阶段读取寄存器
// ==========================================================================
class Issue_ReadReg_Payload extends Bundle {
  val pc                   = UInt(32.W)
  val inst                 = UInt(32.W)
  val imm                  = UInt(32.W)
  val type_decode_together = UInt(9.W)
  val predict_taken        = Bool()
  val predict_target       = UInt(32.W)
  val bht_meta             = UInt(2.W)
  val robIdx               = UInt(CPUConfig.robPtrWidth.W)
  val regWriteEnable       = Bool()
  val sbIdx                = UInt(CPUConfig.sbPtrWidth.W)
  val isSbAlloc            = Bool()
}

// ==========================================================================
// ReadReg_Execute_Payload：ReadReg → Execute 流水线数据
// ==========================================================================
// ReadReg 阶段读取寄存器堆获得 rs1/rs2 的值，作为旁路链的兜底值
// ==========================================================================
class ReadReg_Execute_Payload extends Bundle {
  val pc                   = UInt(32.W)
  val inst                 = UInt(32.W)
  val robIdx               = UInt(CPUConfig.robPtrWidth.W)
  val src1Data             = UInt(32.W)       // ReadReg 阶段读到的 rs1 数据（转发链兜底值）
  val src2Data             = UInt(32.W)       // ReadReg 阶段读到的 rs2 数据（转发链兜底值）
  val imm                  = UInt(32.W)       // 立即数
  val type_decode_together = UInt(9.W)        // 指令类型独热编码
  val predict_taken        = Bool()
  val predict_target       = UInt(32.W)
  val bht_meta             = UInt(2.W)
  val regWriteEnable       = Bool()           // 该指令是否需要写回寄存器
  val sbIdx                = UInt(CPUConfig.sbPtrWidth.W)
  val isSbAlloc            = Bool()
}

// ==========================================================================
// Execute → Memory：执行结果 + 分支验证信息
// ==========================================================================
class Execute_Memory_Payload extends Bundle {
  val pc                   = UInt(32.W)
  val inst_funct3          = UInt(3.W)        // Load/Store 的 funct3
  val inst_rd              = UInt(5.W)        // 目标寄存器
  val data                 = UInt(32.W)       // ALU 结果 / 地址 / 链接地址等
  val reg_rdata2           = UInt(32.W)       // Store 的写入数据（rs2 的值）
  val type_decode_together = UInt(9.W)
  val robIdx               = UInt(CPUConfig.robPtrWidth.W)
  val regWriteEnable       = Bool()
  val isBranch             = Bool()           // 是否为分支指令
  val isJump               = Bool()           // 是否为 JAL/JALR
  val predict_taken        = Bool()           // Fetch 阶段的预测
  val predict_target       = UInt(32.W)
  val actual_taken         = Bool()           // Execute 阶段计算出的实际结果
  val actual_target        = UInt(32.W)       // 实际跳转目标
  val mispredict           = Bool()           // 是否预测错误
  val bht_meta             = UInt(2.W)
  val sbIdx                = UInt(CPUConfig.sbPtrWidth.W)   // StoreBuffer 指针
  val isSbAlloc            = Bool()                         // 是否分配了 StoreBuffer 表项
}

// ==========================================================================
// Memory → Refresh：访存结果
// ==========================================================================
class Memory_Refresh_Payload extends Bundle {
  val pc                   = UInt(32.W)
  val inst_rd              = UInt(5.W)
  val data                 = UInt(32.W)       // 最终数据（ALU 结果或 Load 读回值）
  val type_decode_together = UInt(9.W)
  val robIdx               = UInt(CPUConfig.robPtrWidth.W)
  val regWriteEnable       = Bool()
  val isBranch             = Bool()
  val isJump               = Bool()
  val predict_taken        = Bool()
  val predict_target       = UInt(32.W)
  val actual_taken         = Bool()
  val actual_target        = UInt(32.W)
  val mispredict           = Bool()
  val bht_meta             = UInt(2.W)
}

// ==========================================================================
// StoreBufferEntry：StoreBuffer 中每个表项存储的字段
// ==========================================================================
class StoreBufferEntry extends Bundle {
  val valid     = Bool()           // 表项是否有效（已分配）
  val addrValid = Bool()           // 地址是否已写入（Execute 阶段写入地址和数据）
  val committed = Bool()           // 是否已提交（Commit 阶段标记）
  val addr      = UInt(32.W)       // Store 地址
  val data      = UInt(32.W)       // Store 数据
  val mask      = UInt(3.W)        // Store 宽度掩码（funct3）
  val robIdx    = UInt(CPUConfig.robPtrWidth.W) // 对应的 ROB 指针（用于判断年龄）
}

// ============================================================================
// ROBEntry —— ROB 中每个表项存储的字段
// ============================================================================
// 注意：Store 的地址/数据/掩码信息已移至 StoreBuffer 管理，ROB 仅标记 isStore
// ============================================================================
class ROBEntry extends Bundle {
  val done            = Bool()           // 该指令是否已执行完毕（Refresh 标记）
  val pc              = UInt(32.W)       // 指令 PC
  val inst            = UInt(32.W)       // 原始指令（调试用）
  val rd              = UInt(5.W)        // 目标寄存器编号
  val regWriteEnable  = Bool()           // 是否需要写回寄存器
  val regWBData       = UInt(32.W)       // 写回寄存器的结果（Store 指令为 0，Commit 由 StoreBuffer 实现）
  val isLoad          = Bool()           // 是否为 Load 指令
  val isStore         = Bool()           // 是否为 Store 指令
  val isBranch        = Bool()           // 是否为分支指令
  val isJump          = Bool()           // 是否为 JAL/JALR
  val predict_taken   = Bool()           // Fetch 阶段的预测结果
  val predict_target  = UInt(32.W)       // 预测目标地址
  val actual_taken    = Bool()           // Execute 阶段计算出的实际跳转结果
  val actual_target   = UInt(32.W)       // 实际跳转目标
  val mispredict      = Bool()           // 是否预测错误
  val exception       = Bool()           // 是否有异常（预留）
  val bht_meta        = UInt(2.W)        // BHT 状态快照
}

// ============================================================================
// ROB 模块对外接口 Bundle 定义
// ============================================================================

// ---- ROB 分配数据（单条指令的分配信息）----
class ROBAllocData extends Bundle {
  val pc            = UInt(32.W)
  val inst          = UInt(32.W)
  val regWen        = Bool()
  val rd            = UInt(5.W)
  val isLoad        = Bool()
  val isStore       = Bool()
  val isBranch      = Bool()
  val isJump        = Bool()
  val predictTaken  = Bool()
  val predictTarget = UInt(32.W)
  val bhtMeta       = UInt(2.W)
}

// ---- ROB ↔ Dispatch 4-wide 分配接口 ----
// 方向以 Dispatch（发送方）为基准
class ROBMultiAllocIO extends Bundle {
  val request  = Output(UInt(3.W))                            // 请求分配的表项数量（0-4）
  val canAlloc = Input(Bool())                                // ROB 是否有足够空间
  val data     = Output(Vec(4, new ROBAllocData))             // 每条指令的分配数据
  val idxs     = Input(Vec(4, UInt(CPUConfig.robPtrWidth.W))) // ROB 返回的分配指针
}

// ---- ROB ↔ Refresh 完成标记接口 ----
// Refresh 阶段标记 ROB 表项已执行完毕，写入结果和分支实际信息
class ROBRefreshIO extends Bundle {
  val valid        = Output(Bool())
  val idx          = Output(UInt(CPUConfig.robPtrWidth.W))    // 完成的 ROB 指针（含回绕位）
  val regWBData    = Output(UInt(32.W))
  val actualTaken  = Output(Bool())
  val actualTarget = Output(UInt(32.W))
  val mispredict   = Output(Bool())
}

// ---- ROB 提交接口 ----
// 注意：Store 写入内存由 StoreBuffer 在 Commit 时处理，ROB 不再携带 store 信息
class ROBCommitIO extends Bundle {
  val valid        = Output(Bool())     // 本周期是否有指令提交
  val pc           = Output(UInt(32.W))
  val inst         = Output(UInt(32.W))
  val rd           = Output(UInt(5.W))
  val regWen       = Output(Bool())     // 是否写寄存器
  val regWBData    = Output(UInt(32.W)) // 写回寄存器的结果（Store 指令为 0，Commit 由 StoreBuffer 实现）
  val isStore      = Output(Bool())     // 是否为 Store 指令（通知 StoreBuffer 提交）
  val isBranch     = Output(Bool())     // 用于 BHT 训练（预留）
  val isJump       = Output(Bool())
  val mispredict   = Output(Bool())     // 用于调试观测
  val actualTaken  = Output(Bool())
  val actualTarget = Output(UInt(32.W))
  val predictTaken = Output(Bool())
  val bhtMeta      = Output(UInt(2.W))
}
