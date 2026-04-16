package mycpu.dataLane

import chisel3._
import chisel3.util.Valid
import chisel3.util.log2Ceil
import mycpu.CPUConfig

// ============================================================================
// 流水线各级之间传递的数据包（Payload）定义
// ============================================================================
// 10 级流水线（无寄存器重命名版本）：
//   Fetch(4,BPU) → [FetchBuffer(4in/4out)] → Decode(4) → [DecRenDff]
//   → Rename(4,仅计数) → [RenDisDff] → Dispatch(4,ROB alloc) → [IssueQueue(4in/4out)]
//   → Issue(1,load-use) → [IssRRDff] → ReadReg(1) → [RRExDff]
//   → Execute(1,branch verify) → [ExMemDff] → Memory(1,redirect) → [MemRefDff]
//   → Refresh(1) → Commit(ROB)

// ==========================================================================
// FetchBuffer：存储于环形缓冲区中的指令信息
// ==========================================================================
class FetchBufferEntry extends Bundle {
  val pc             = UInt(32.W) // 指令 PC
  val inst           = UInt(32.W) // 32 位指令
  val predict_taken  = Bool()     // Fetch 阶段 BPU 预测结果
  val predict_target = UInt(32.W) // 预测跳转目标
  val bht_meta       = UInt(2.W)  // BHT 状态快照
  val valid = Bool()              // 有效指示位
}

// ==========================================================================
// DecodedInst：单条译码结果（4-wide Decode 的每路输出）
// ==========================================================================
class DecodedInst extends FetchBufferEntry {  // 其他变量直接继承自上一阶段
  val imm                  = UInt(32.W) // 符号扩展后的立即数
  val type_decode_together = UInt(9.W)  // 指令类型独热编码 {U,JAL,JALR,B,L,I,S,R,Other}
  val regWriteEnable = Bool() // 是否需要写回寄存器（Decode 阶段计算）
}

// ==========================================================================
// Rename_Dispatch_Payload：4-wide Rename 输出包（Rename → RenDisDff → Dispatch）
// 无寄存器重命名版本：直接使用 DecodedInst，不添加物理寄存器映射信息
// ==========================================================================
class Rename_Dispatch_Payload extends Bundle {
  val entries = Vec(4, new DecodedInst)    // 直接透传译码结果，不含物理寄存器映射
  val validCount = UInt(3.W) // 有效指令数量（0-4），用于 ROB 分配派遣计数
  val storeCount = UInt(3.W) // 既有效又写回内存的指令数量（0-4），用于 StoreBuffer 分配计数
}

// ==========================================================================
// DispatchEntry：经过 Dispatch 阶段后的单条指令信息（含 ROB 索引）
// 无寄存器重命名版本：不含物理寄存器映射信息
// ==========================================================================
class DispatchEntry extends DecodedInst {
  val robIdx         = UInt(CPUConfig.robPtrWidth.W) // 分配到的 ROB 指针（含回绕位）
  val sbIdx          = UInt(CPUConfig.sbIdxWidth.W)  // 分配到的 StoreBuffer 物理槽位索引（仅 Store 有效）
  val isSbAlloc      = Bool()                        // 是否在 StoreBuffer 中分配了表项
  val storeSeqSnap   = UInt(CPUConfig.storeSeqWidth.W) // 分配时刻的 nextStoreSeq 快照（Load 用于 S2L 查询边界）
}

// ==========================================================================
// DispatchPacket：4-wide Dispatch 输出包（Dispatch → IssueQueue）
// ==========================================================================
class DispatchPacket extends Bundle {
  val entries    = Vec(4, new DispatchEntry)
  val validCount = UInt(3.W) // 本次派遣的有效指令数量（0-4）
}

// ==========================================================================
// Issue_ReadReg_Payload：Issue → ReadReg 流水线数据
// 无寄存器重命名版本：不含物理寄存器映射信息，rs1/rs2 从 inst 字段提取
// ==========================================================================
class Issue_ReadReg_Payload extends Bundle {
  val pc             = UInt(32.W) // 指令 PC
  val inst           = UInt(32.W) // 32 位指令
  val imm                  = UInt(32.W) // 符号扩展后的立即数
  val type_decode_together = UInt(9.W)  // 指令类型独热编码 {U,JAL,JALR,B,L,I,S,R,Other}
  val predict_taken  = Bool()     // Fetch 阶段 BPU 预测结果
  val predict_target = UInt(32.W) // 预测跳转目标
  val bht_meta       = UInt(2.W)  // BHT 状态快照
  val regWriteEnable = Bool()                        // 是否需要写回寄存器
  val robIdx         = UInt(CPUConfig.robPtrWidth.W) // 分配到的 ROB 指针（含回绕位）
  val sbIdx          = UInt(CPUConfig.sbIdxWidth.W)  // 分配到的 StoreBuffer 物理槽位索引（仅 Store 有效）
  val isSbAlloc      = Bool()                        // 是否在 StoreBuffer 中分配了表项
  val storeSeqSnap   = UInt(CPUConfig.storeSeqWidth.W) // storeSeq 快照（Load 用于 S2L 查询边界）
}

// ==========================================================================
// ReadReg_Execute_Payload：ReadReg → Execute 流水线数据
// ReadReg 阶段读取寄存器堆获得 rs1/rs2 的值，作为旁路链的兜底值
// ==========================================================================
class ReadReg_Execute_Payload extends Issue_ReadReg_Payload {
  val src1Data = UInt(32.W) // ReadReg 阶段读到的 rs1 数据（转发链兜底值）
  val src2Data = UInt(32.W) // ReadReg 阶段读到的 rs2 数据（转发链兜底值）
}

// ==========================================================================
// Execute → Memory：执行结果 + 分支验证信息
// 无寄存器重命名版本：使用逻辑寄存器 inst_rd 进行旁路匹配
// ==========================================================================
class Execute_Memory_Payload extends Bundle {
  val pc                   = UInt(32.W)
  val inst_funct3          = UInt(3.W)  // Load/Store 的 funct3
  val inst_rd              = UInt(5.W)  // 逻辑目标寄存器（旁路匹配 + difftest）
  val data                 = UInt(32.W) // ALU 结果 / 地址 / 链接地址等
  val reg_rdata2           = UInt(32.W) // Store 的写入数据（rs2 的值）
  val type_decode_together = UInt(9.W)
  val robIdx               = UInt(CPUConfig.robPtrWidth.W)
  val regWriteEnable       = Bool()
  val isBranch             = Bool()     // 是否为分支指令
  val isJump               = Bool()     // 是否为 JAL/JALR
  val predict_taken        = Bool()     // Fetch 阶段预测是否跳转
  val predict_target       = UInt(32.W) // Fetch 阶段的跳转目标
  val actual_taken         = Bool()     // Execute 阶段计算出的实际结果
  val actual_target        = UInt(32.W) // 实际跳转目标
  val mispredict           = Bool()     // 是否预测错误
  val bht_meta             = UInt(2.W)
  val isSbAlloc            = Bool()     // 是否分配了 StoreBuffer 表项
  val sbIdx                = UInt(CPUConfig.sbIdxWidth.W) // StoreBuffer 物理槽位索引
  val storeSeqSnap         = UInt(CPUConfig.storeSeqWidth.W) // storeSeq 快照（Load 用于 S2L 查询；mispredict 用于回滚）
}

// ==========================================================================
// Memory → Refresh：访存结果
// 无寄存器重命名版本：使用逻辑寄存器 inst_rd 进行 REG 写入
// ==========================================================================
class Memory_Refresh_Payload extends Bundle {
  val pc                   = UInt(32.W)
  val inst_rd              = UInt(5.W)     // 逻辑目标寄存器（REG 写入用 + difftest）
  val data                 = UInt(32.W) // 最终数据（ALU 结果或 Load 读回值）
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
// 新架构：物理槽位由 FreeList 管理，逻辑年龄由 storeSeq 表示
class StoreBufferEntry extends Bundle {
  val valid     = Bool()                          // 表项是否有效（已分配）
  val addrValid = Bool()                          // 地址是否已写入（Memory 阶段写入地址和数据）
  val committed = Bool()                          // 是否已被 ROB 提交（可以 drain 到内存）
  val addr      = UInt(32.W)                      // Store 地址
  val data      = UInt(32.W)                      // Store 数据
  val mask      = UInt(3.W)                       // Store 宽度掩码（funct3）
  val storeSeq  = UInt(CPUConfig.storeSeqWidth.W) // 逻辑年龄：全局单调递增序号，越小越老
}

// ==========================================================================
// StoreBuffer IO Bundle 定义
// ==========================================================================
// 以下 Bundle 定义了 StoreBuffer 的各个接口，方向统一以"使用者侧"（Dispatch/
// Memory/myCPU）为基准。StoreBuffer 内部使用 Flipped() 翻转方向。

// ---- 分配接口（Dispatch 阶段请求分配 Store 表项）----
// 新架构：返回 FreeList 分配的物理槽位索引和对应的 storeSeq 逻辑年龄
class SBAllocIO extends Bundle {
  val request      = Output(UInt(3.W))                              // 请求分配的 Store 表项数（0~4）
  val canAlloc     = Input(Bool())                                  // StoreBuffer 是否有足够空间
  val idxs         = Input(Vec(4, UInt(CPUConfig.sbIdxWidth.W)))    // 分配的物理槽位索引（FreeList 返回）
  val storeSeqs    = Input(Vec(4, UInt(CPUConfig.storeSeqWidth.W))) // 分配的 storeSeq 逻辑年龄
  val nextStoreSeq = Input(UInt(CPUConfig.storeSeqWidth.W))         // 当前 nextStoreSeq 快照（Load 用于 storeSeqSnap）
}

// ---- 写入接口（Memory 阶段写入 Store 地址和数据）----
class SBWriteIO extends Bundle {
  val valid = Bool()                       // 写入使能
  val idx   = UInt(CPUConfig.sbIdxWidth.W) // 要写的物理槽位索引
  val addr  = UInt(32.W)                   // Store 地址
  val data  = UInt(32.W)                   // Store 数据
  val mask  = UInt(3.W)                    // Store 宽度掩码
}

// ---- 查询接口（Memory 阶段 Load 指令查询 Store-to-Load 转发）----
// 新架构：使用 storeSeqSnap 精确界定"更老的 Store"边界
class SBQueryIO extends Bundle {
  val valid        = Output(Bool())                          // 是否进行查询（Load 有效时）
  val addr         = Output(UInt(32.W))                      // Load 的地址
  val storeSeqSnap = Output(UInt(CPUConfig.storeSeqWidth.W)) // Load 的 storeSeq 快照：只查 storeSeq < snap 的表项
  val hit          = Input(Bool())                           // 是否有更老的 Store 地址命中
  val data         = Input(UInt(32.W))                       // 命中时转发的 Store 数据
  val pending      = Input(Bool())                           // 是否有更老的 Store 地址未知（需要停顿）
}

// ---- 提交标记接口（ROB 提交 Store 时，标记 SB 表项为 committed）----
// 新架构：commit 仅标记 committed 标志，不直接写内存
class SBCommitIO extends Bundle {
  val valid    = Output(Bool())                             // 本周期 ROB 是否提交一条 Store 指令
  val storeSeq = Output(UInt(CPUConfig.storeSeqWidth.W))    // 被提交的 Store 的 storeSeq（用于定位表项）
}

// ---- Drain 接口（将已 committed 的表项写入内存，与 commit 解耦）----
// StoreBuffer 内部按最老 committed 表项的顺序 drain
class SBDrainIO extends Bundle {
  val valid = Output(Bool())                                // 本周期是否有 Store 需要写入内存
  val addr  = Output(UInt(32.W))                            // 写入地址
  val data  = Output(UInt(32.W))                            // 写入数据
  val mask  = Output(UInt(3.W))                             // 写入宽度掩码
}

// ---- 回滚接口（Memory 重定向时按 storeSeq 精确回滚 StoreBuffer）----
// 新架构：使用 storeSeqSnap 精确清除年轻表项
class SBRollbackIO extends Bundle {
  val valid        = Output(Bool())                          // 回滚使能
  val storeSeqSnap = Output(UInt(CPUConfig.storeSeqWidth.W)) // 误预测指令的 storeSeqSnap：清除 storeSeq >= snap 的所有未提交表项
}

// ============================================================================
// ROB 模块对外接口 Bundle 定义（无寄存器重命名版本）
// ============================================================================
// ---- ROB 分配数据（单条指令的分配信息，不含物理寄存器映射）----
class ROBAllocData extends Bundle {
  val pc            = UInt(32.W) // 指令 PC
  val inst          = UInt(32.W) // 原始指令（调试用）
  val rd            = UInt(5.W)  // 逻辑目标寄存器编号
  val regWen        = Bool()     // 是否需要写回寄存器
  val isLoad        = Bool()     // 是否为 Load 指令
  val isStore       = Bool()     // 是否为 Store 指令
  val isBranch      = Bool()     // 是否为分支指令
  val isJump        = Bool()     // 是否为 JAL/JALR
  val predictTaken  = Bool()     // Fetch 阶段的预测结果
  val predictTarget = UInt(32.W) // 预测目标地址
  val bhtMeta       = UInt(2.W)  // BHT 状态快照
  val storeSeq      = UInt(CPUConfig.storeSeqWidth.W) // Store 的 storeSeq 逻辑年龄（仅 Store 有效，Commit 时传给 SB）
}

// ---- ROB ↔ Dispatch 4-wide 分配接口 ----
class ROBMultiAllocIO extends Bundle { // 方向以 Dispatch（发送方）为基准
  val canAlloc = Input(Bool())                                // ROB 是否有足够空间
  val request  = Output(UInt(3.W))                            // 请求分配的表项数量（0-4）
  val data     = Output(Vec(4, new ROBAllocData))             // 每条指令的分配数据
  val idxs     = Input(Vec(4, UInt(CPUConfig.robPtrWidth.W))) // ROB 返回的分配指针
}

// ============================================================================
// ROBEntry —— ROB 中每个表项存储的字段（无寄存器重命名版本）
// ============================================================================
class ROBEntry extends ROBAllocData {
  val done         = Bool()     // 该指令是否已执行完毕（Refresh 标记）
  val regWBData    = UInt(32.W) // 写回寄存器的结果（Store 指令为 0，Commit 由 StoreBuffer 实现）
  val actualTaken  = Bool()     // Execute 阶段计算出的实际跳转结果
  val actualTarget = UInt(32.W) // 实际跳转目标
  val mispredict   = Bool()     // 是否预测错误
  val exception    = Bool()     // 是否有异常（预留）
}

// ---- ROB ↔ Refresh 完成标记接口（无寄存器重命名版本）----
// Refresh 阶段标记 ROB 表项已执行完毕，写入结果和分支实际信息
// 同时携带逻辑寄存器 rd 信息用于 REG 写入
class ROBRefreshIO extends Bundle {
  val valid          = Output(Bool())
  val idx            = Output(UInt(CPUConfig.robPtrWidth.W))    // 完成的 ROB 指针（含回绕位）
  val regWBData      = Output(UInt(32.W))
  val actualTaken    = Output(Bool())
  val actualTarget   = Output(UInt(32.W))
  val mispredict     = Output(Bool())
  val rd             = Output(UInt(5.W))                        // 逻辑目的寄存器（REG 写入用）
  val regWriteEnable = Output(Bool())                           // 是否需要写回（控制 REG 写入）
}

// ---- ROB 提交接口（无寄存器重命名版本）----
class ROBCommitIO extends Bundle {
  val valid        = Output(Bool())     // 本周期是否有指令提交
  val pc           = Output(UInt(32.W))
  val inst         = Output(UInt(32.W))
  val rd           = Output(UInt(5.W))
  val regWen       = Output(Bool())     // 是否写寄存器
  val regWBData    = Output(UInt(32.W)) // 写回寄存器的结果
  val isStore      = Output(Bool())     // 是否为 Store 指令（通知 StoreBuffer 提交）
  val storeSeq     = Output(UInt(CPUConfig.storeSeqWidth.W)) // Store 的 storeSeq（Commit 时传给 SB 定位表项）
  val isBranch     = Output(Bool())     // 用于 BHT 训练（预留）
  val isJump       = Output(Bool())
  val mispredict   = Output(Bool())     // 用于调试观测
  val actualTaken  = Output(Bool())
  val actualTarget = Output(UInt(32.W))
  val predictTaken = Output(Bool())
  val bhtMeta      = Output(UInt(2.W))
}
