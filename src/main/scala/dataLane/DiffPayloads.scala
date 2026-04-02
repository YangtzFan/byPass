package mycpu.dataLane

import chisel3._
import chisel3.util.Valid
import mycpu.CPUConfig

// ============================================================================
// 流水线各级之间传递的数据包（Payload）定义
// ============================================================================
// 8 级流水线：Fetch(BPU) → FetchBuffer(4in/4out) → Decode(4-wide) → DecRenDff
//            → Rename(4-wide,ROB alloc) → RenameBuffer(4in/1out) → Dispatch(1-wide)
//            → DispExDff → Execute → ExMemDff → MEM(redirect) → MemWbDff → WB → Commit

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
// Decode_Rename_Payload-wide 译码结果（Decode → Rename）
// ==========================================================================
class Decode_Rename_Payload extends Bundle {
  val insts = Vec(4, new DecodedInst) // 4 条译码结果
  val valid = Vec(4, Bool())          // 有效指示位
}

// ==========================================================================
// RenameEntry：经过 Rename 阶段后的单条指令信息（含 ROB 索引）
// ==========================================================================
class RenameEntry extends Bundle {
  val pc                   = UInt(32.W)
  val inst                 = UInt(32.W)
  val imm                  = UInt(32.W)
  val type_decode_together = UInt(9.W)
  val predict_taken        = Bool()
  val predict_target       = UInt(32.W)
  val bht_meta             = UInt(2.W)
  val robIdx               = UInt(CPUConfig.robPtrWidth.W)  // 分配到的 ROB 指针（含回绕位）
  val regWriteEnable       = Bool()                         // 是否需要写回寄存器
}

// ==========================================================================
// RenamePacket：4-wide Rename 输出包（Rename → RenameBuffer）
// ==========================================================================
class RenamePacket extends Bundle {
  val entries = Vec(4, new RenameEntry)
  val valid   = Vec(4, Bool())
  val count   = UInt(3.W) // 有效指令数量（0-4），用于 RenameBuffer 入队计数
}

// ==========================================================================
// Dispatch → Execute：含源操作数数据和 ROB 索引
// ==========================================================================
class Dispatch_Execute_Payload extends Bundle {
  val pc                   = UInt(32.W)
  val inst                 = UInt(32.W)
  val robIdx               = UInt(CPUConfig.robPtrWidth.W)  // ROB 指针（含回绕位）
  val src1Data             = UInt(32.W)       // Dispatch 阶段读到的 rs1 数据（转发链兜底值）
  val src2Data             = UInt(32.W)       // Dispatch 阶段读到的 rs2 数据（转发链兜底值）
  val imm                  = UInt(32.W)       // 立即数
  val type_decode_together = UInt(9.W)        // 指令类型独热编码
  val predict_taken        = Bool()
  val predict_target       = UInt(32.W)
  val bht_meta             = UInt(2.W)
  val regWriteEnable       = Bool()           // 该指令是否需要写回寄存器
}

// ==========================================================================
// Execute → MEM：执行结果 + 分支验证信息
// ==========================================================================
class Execute_MEM_Payload extends Bundle {
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
  val actual_target         = UInt(32.W)       // 实际跳转目标
  val mispredict           = Bool()           // 是否预测错误
  val bht_meta             = UInt(2.W)
}

// ==========================================================================
// MEM → WB：访存结果 + Store 信息
// ==========================================================================
class MEM_WB_Payload extends Bundle {
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
  val actual_target         = UInt(32.W)
  val mispredict           = Bool()
  val isStore              = Bool()           // 是否为 Store 指令
  val store_addr           = UInt(32.W)       // Store 地址
  val store_data           = UInt(32.W)       // Store 数据
  val store_mask           = UInt(3.W)        // Store 宽度掩码
  val bht_meta             = UInt(2.W)
}

// ============================================================================
// ROBEntry —— ROB 中每个表项存储的字段
// ============================================================================
class ROBEntry extends Bundle {
  val done            = Bool()           // 该指令是否已执行完毕（WB 标记）
  val pc              = UInt(32.W)       // 指令 PC
  val inst            = UInt(32.W)       // 原始指令（调试用）
  val rd              = UInt(5.W)        // 目标寄存器编号
  val regWriteEnable  = Bool()           // 是否需要写回寄存器
  val result          = UInt(32.W)       // 计算结果
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
  val store_addr      = UInt(32.W)       // Store 地址
  val store_data      = UInt(32.W)       // Store 数据
  val store_mask      = UInt(3.W)        // Store 宽度掩码
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

// ---- ROB ↔ Rename 4-wide 分配接口 ----
// 方向以 Rename（发送方）为基准
class ROBMultiAllocIO extends Bundle {
  val request  = Output(UInt(3.W))                            // 请求分配的表项数量（0-4）
  val canAlloc = Input(Bool())                                // ROB 是否有足够空间
  val data     = Output(Vec(4, new ROBAllocData))             // 每条指令的分配数据
  val idxs     = Input(Vec(4, UInt(CPUConfig.robPtrWidth.W))) // ROB 返回的分配指针
}

// ---- ROB ↔ WB 完成标记接口 ----
class ROBWbIO extends Bundle {
  val valid        = Output(Bool())
  val idx          = Output(UInt(CPUConfig.robPtrWidth.W))    // 完成的 ROB 指针（含回绕位）
  val result       = Output(UInt(32.W))
  val actualTaken  = Output(Bool())
  val actualTarget = Output(UInt(32.W))
  val mispredict   = Output(Bool())
  val storeAddr    = Output(UInt(32.W))
  val storeData    = Output(UInt(32.W))
  val storeMask    = Output(UInt(3.W))
}

// ---- ROB 提交接口 ----
class ROBCommitIO extends Bundle {
  val valid        = Output(Bool())     // 本周期是否有指令提交
  val pc           = Output(UInt(32.W))
  val inst         = Output(UInt(32.W))
  val rd           = Output(UInt(5.W))
  val regWen       = Output(Bool())     // 是否写寄存器
  val result       = Output(UInt(32.W))
  val isStore      = Output(Bool())
  val storeAddr    = Output(UInt(32.W))
  val storeData    = Output(UInt(32.W))
  val storeMask    = Output(UInt(3.W))
  val isBranch     = Output(Bool())     // 用于 BHT 训练（预留）
  val isJump       = Output(Bool())
  val mispredict   = Output(Bool())     // 用于调试观测
  val actualTaken  = Output(Bool())
  val actualTarget = Output(UInt(32.W))
  val predictTaken = Output(Bool())
  val bhtMeta      = Output(UInt(2.W))
}
