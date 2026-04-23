package mycpu.dataLane

import chisel3._
import chisel3.util.Valid
import chisel3.util.log2Ceil
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
// RenameEntry：经过 Rename 阶段后的单条指令信息
// ==========================================================================
// Rename 阶段完成逻辑寄存器到物理寄存器的映射。
// psrc1/psrc2 是源操作数对应的物理寄存器编号（从 RAT 查询 + rename bypass）
// pdst 是目的寄存器分配的新物理寄存器编号（从 FreeList 分配）
// stalePdst 是目的逻辑寄存器在 RAT 中的旧物理映射（用于 Commit 时释放）
// psrc1Ready/psrc2Ready 表示源操作数是否已经就绪（ReadyTable 查询）
// ==========================================================================
class RenameEntry extends DecodedInst { // 其他变量直接继承自上一阶段
  val psrc1          = UInt(CPUConfig.prfAddrWidth.W) // 物理源寄存器 1 编号
  val psrc2          = UInt(CPUConfig.prfAddrWidth.W) // 物理源寄存器 2 编号
  val pdst           = UInt(CPUConfig.prfAddrWidth.W) // 分配的物理目的寄存器编号
  val stalePdst      = UInt(CPUConfig.prfAddrWidth.W) // 旧的物理目的寄存器映射（用于 Commit 释放）
  val ldst           = UInt(5.W)                      // 逻辑目的寄存器编号（rd）
  val checkpointIdx  = UInt(CPUConfig.ckptPtrWidth.W) // 分支 checkpoint 全指针（含回绕位，用于 BCT 恢复）
  val hasCheckpoint  = Bool()                         // 该指令是否为一组中的快照型分支（对应 BCT 保存的那一个）
}

// ==========================================================================
// Rename_Dispatch_Payload：4-wide Rename 输出包（Rename → RenDisDff → Dispatch）
// ==========================================================================
class Rename_Dispatch_Payload extends Bundle {
  val entries = Vec(4, new RenameEntry)
  val validCount = UInt(3.W) // 有效指令数量（0-4），用于 ROB 分配派遣计数
  val storeCount = UInt(3.W) // 既有效又写回内存的指令数量（0-4），用于 StoreBuffer 分配计数
}

// ==========================================================================
// DispatchEntry：经过 Dispatch 阶段后的单条指令信息（含 ROB 索引 + 物理寄存器映射）
// ==========================================================================
class DispatchEntry extends DecodedInst {
  val robIdx         = UInt(CPUConfig.robPtrWidth.W) // 分配到的 ROB 指针（含回绕位）
  val sbIdx          = UInt(CPUConfig.sbIdxWidth.W)  // 分配到的 StoreBuffer 物理槽位索引（仅 Store 有效）
  val isSbAlloc      = Bool()                        // 是否在 StoreBuffer 中分配了表项
  val storeSeqSnap   = UInt(CPUConfig.storeSeqWidth.W) // 分配时刻的 nextStoreSeq 快照（Load 用于 S2L 查询边界）
  val psrc1          = UInt(CPUConfig.prfAddrWidth.W)  // 物理源寄存器 1 编号
  val psrc2          = UInt(CPUConfig.prfAddrWidth.W)  // 物理源寄存器 2 编号
  val pdst           = UInt(CPUConfig.prfAddrWidth.W)  // 分配的物理目的寄存器编号
  val stalePdst      = UInt(CPUConfig.prfAddrWidth.W)  // 旧的物理目的寄存器映射（Commit 释放用）
  val ldst           = UInt(5.W)                        // 逻辑目的寄存器编号（rd）
  val checkpointIdx  = UInt(CPUConfig.ckptPtrWidth.W) // 分支 checkpoint 全指针（含回绕位）
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
// ==========================================================================
// Issue 阶段从 IssueQueue 取出 1 条指令，传给 ReadReg 阶段读取物理寄存器
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
  val psrc1          = UInt(CPUConfig.prfAddrWidth.W)  // 物理源寄存器 1 编号
  val psrc2          = UInt(CPUConfig.prfAddrWidth.W)  // 物理源寄存器 2 编号
  val pdst           = UInt(CPUConfig.prfAddrWidth.W)  // 物理目的寄存器编号
  val stalePdst      = UInt(CPUConfig.prfAddrWidth.W)  // 旧的物理目的寄存器映射
  val ldst           = UInt(5.W)                      // 逻辑目的寄存器编号（rd）
  val checkpointIdx  = UInt(CPUConfig.ckptPtrWidth.W) // 分支 checkpoint 全指针（含回绕位）
}

// ==========================================================================
// ReadReg_Execute_Payload：ReadReg → Execute 流水线数据
// ==========================================================================
// ReadReg 阶段读取寄存器堆获得 rs1/rs2 的值，作为旁路链的兜底值
// ==========================================================================
class ReadReg_Execute_Payload extends Issue_ReadReg_Payload {
  val src1Data = UInt(32.W) // ReadReg 阶段读到的 rs1 数据（转发链兜底值）
  val src2Data = UInt(32.W) // ReadReg 阶段读到的 rs2 数据（转发链兜底值）
}

// ==========================================================================
// Execute → Memory：执行结果 + 分支验证信息
// ==========================================================================
class Execute_Memory_Payload extends Bundle {
  val pc                   = UInt(32.W)
  val inst_funct3          = UInt(3.W)  // Load/Store 的 funct3
  val inst_rd              = UInt(5.W)  // 逻辑目标寄存器（difftest 用）
  val pdst                 = UInt(CPUConfig.prfAddrWidth.W) // 物理目的寄存器（旁路转发匹配用）
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
  val checkpointIdx        = UInt(CPUConfig.ckptPtrWidth.W) // 分支 checkpoint 全指针（含回绕位，mispredict 恢复用）
}

// ==========================================================================
// Memory → Refresh：访存结果
// ==========================================================================
class Memory_Refresh_Payload extends Bundle {
  val pc                   = UInt(32.W)
  val inst_rd              = UInt(5.W)     // 逻辑目标寄存器（difftest 用）
  val pdst                 = UInt(CPUConfig.prfAddrWidth.W) // 物理目的寄存器（PRF 写入用）
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
  val addr      = UInt(32.W)                      // Store 原始完整地址（difftest 用）
  val data      = UInt(32.W)                      // Store 原始数据（rs2 值，difftest 用）
  val mask      = UInt(3.W)                       // Store 原始宽度掩码（funct3，difftest 用）
  val byteMask  = UInt(4.W)                       // 字节写使能掩码（sb/sh/sw 对应 1/2/4 位使能）
  val byteData  = UInt(32.W)                      // 字节对齐后的写数据（已按字内偏移摆好位置）
  val storeSeq  = UInt(CPUConfig.storeSeqWidth.W) // 逻辑年龄：全局单调递增序号，越小越老
}

// ==========================================================================
// StoreBuffer IO Bundle 定义
// ==========================================================================
// 以下 Bundle 定义了 StoreBuffer 的各个接口，方向统一以"使用者侧"（Dispatch/
// Memory/myCPU）为基准。StoreBuffer 内部使用 Flipped() 翻转方向。

// ---- 分配接口（Dispatch 阶段请求分配 Store 表项）----
// 这里特意把“资源余量预览”和“真正执行分配”拆开：
//   1. request 仍然只表示本拍真正要提交给 StoreBuffer 的分配数量；
//   2. availCount 只反映当前还能提供多少空闲槽位，不携带任何“最坏情况 4 个 Store”假设；
//   3. Dispatch 先根据 Rename 已经统计好的 storeCount 做精确比较，确认能派发后再把 request 拉高。
// 这样可以避免如下错误背压：
//   - 当前只剩 3 个空槽；
//   - 本拍实际只有 1 条 Store；
//   - 若仍使用“freeCount >= 4”这种保守条件，就会把本来可以派发的批次整拍挡住。
class SBAllocIO extends Bundle {
  val request      = Output(UInt(3.W))                              // 请求分配的 Store 表项数（0~4），仅在真正可以派发时拉高
  val availCount   = Input(UInt(log2Ceil(CPUConfig.sbEntries + 1).W)) // 当前空闲槽位数，仅反映资源余量（不依赖 request）
  val idxs         = Input(Vec(4, UInt(CPUConfig.sbIdxWidth.W)))    // 分配的物理槽位索引（FreeList 返回）
  val storeSeqs    = Input(Vec(4, UInt(CPUConfig.storeSeqWidth.W))) // 分配的 storeSeq 逻辑年龄
  val nextStoreSeq = Input(UInt(CPUConfig.storeSeqWidth.W))         // 当前 nextStoreSeq 快照（Load 用于 storeSeqSnap）
}

// ---- 写入接口（Memory 阶段写入 Store 地址和数据）----
class SBWriteIO extends Bundle {
  val valid    = Bool()                       // 写入使能
  val idx      = UInt(CPUConfig.sbIdxWidth.W) // 要写的物理槽位索引
  val addr     = UInt(32.W)                   // Store 原始完整地址（difftest 用）
  val data     = UInt(32.W)                   // Store 原始数据（rs2 值，difftest 用）
  val mask     = UInt(3.W)                    // Store 原始宽度掩码（funct3，difftest 用）
  val byteMask = UInt(4.W)                    // 字节写使能掩码
  val byteData = UInt(32.W)                   // 字节对齐后的写数据
}

// ---- 查询接口（Memory 阶段 Load 指令查询 Store-to-Load 字节级转发）----
// 新架构：按字节粒度判断转发覆盖情况，支持部分转发 + 外部读合并
class SBQueryIO extends Bundle {
  val valid        = Output(Bool())                          // 是否进行查询（Load 有效时）
  val wordAddr     = Output(UInt(30.W))                      // Load 的字对齐地址（addr[31:2]）
  val loadMask     = Output(UInt(4.W))                       // Load 需要读取的字节掩码
  val storeSeqSnap = Output(UInt(CPUConfig.storeSeqWidth.W)) // Load 的 storeSeq 快照：只查 storeSeq < snap 的表项
  val olderUnknown = Input(Bool())                           // 存在更老的 Store 地址未知（需要停顿等待）
  val fullCover    = Input(Bool())                           // Load 所需字节全部被 StoreBuffer 覆盖（可直接转发）
  val fwdMask      = Input(UInt(4.W))                        // 可转发的字节掩码（每位表示该字节是否由 SB 提供）
  val fwdData      = Input(UInt(32.W))                       // 转发数据（仅 fwdMask 对应字节有效）
}

// ---- 提交候选接口（ROB head 是 Store 时，向 SB 取出待 enqueue 的表项）----
// 新架构中，Store 的真正提交点是“成功进入 AXIStoreQueue”。
// 因此这里不再做“committed 标记”，而是：
//   1. Commit 侧按 storeSeq 向 SB 查询当前 head store 对应的完整信息；
//   2. 若 AXIStoreQueue 同拍 ready，则以 enqSuccess 作为唯一释放条件；
//   3. 未成功握手前，该 Store 仍留在 SB 中，继续参与 rollback / forwarding。
class SBCommitIO extends Bundle {
  val valid      = Output(Bool())                             // 本周期是否要按 storeSeq 查询 SB 中的候选 store
  val storeSeq   = Output(UInt(CPUConfig.storeSeqWidth.W))    // ROB head store 的 storeSeq（用于定位表项）
  val entryValid = Input(Bool())                              // SB 中是否找到了可 enqueue 的候选（含 addr/data 已就绪）
  val addr       = Input(UInt(32.W))                          // 原始 Store 完整字节地址（difftest 用）
  val data       = Input(UInt(32.W))                          // 原始 Store 数据（rs2 值，difftest 用）
  val mask       = Input(UInt(3.W))                           // 原始 Store 宽度掩码（funct3，difftest 用）
  val wordAddr   = Input(UInt(30.W))                          // 字对齐地址（addr[31:2]）
  val wstrb      = Input(UInt(4.W))                           // 字节写使能掩码
  val wdata      = Input(UInt(32.W))                          // 字节对齐后的写数据
  val enqSuccess = Output(Bool())                             // AXIStoreQueue enqueue 成功脉冲：SB 仅在此时释放表项
}

// ---- 回滚接口（Memory 重定向时按 storeSeq 精确回滚 StoreBuffer）----
// 新架构：使用 storeSeqSnap 精确清除年轻表项
class SBRollbackIO extends Bundle {
  val valid        = Output(Bool())                                // 回滚使能
  val storeSeqSnap = Output(UInt(CPUConfig.storeSeqWidth.W))       // 误预测指令的 storeSeqSnap：清除 storeSeq >= snap 的所有未提交表项
}

// ============================================================================
// ROB 模块对外接口 Bundle 定义
// ============================================================================
// ---- ROB 分配数据（单条指令的分配信息）----
class ROBAllocData extends Bundle {
  val pc            = UInt(32.W) // 指令 PC
  val inst          = UInt(32.W) // 原始指令（调试用）
  val rd            = UInt(5.W)  // 逻辑目标寄存器编号
  val regWen        = Bool()     // 是否需要写回寄存器
  val isLoad        = Bool()     // 是否为 Load 指令
  val isStore       = Bool()     // 是否为 Store 指令
  val isBranch      = Bool()     // 是否为分支指令
  val isJump        = Bool()     // 是否为 JAL/JALR
  val hasCheckpoint = Bool()     // 该指令是否保存了 BCT checkpoint（bType || jalr），用于 Commit 时正确释放
  val predictTaken  = Bool()     // Fetch 阶段的预测结果
  val predictTarget = UInt(32.W) // 预测目标地址
  val bhtMeta       = UInt(2.W)  // BHT 状态快照
  val storeSeq      = UInt(CPUConfig.storeSeqWidth.W) // Store 的 storeSeq 逻辑年龄（仅 Store 有效，Commit 时传给 SB）
  val pdst          = UInt(CPUConfig.prfAddrWidth.W)  // 分配的物理目的寄存器编号
  val stalePdst     = UInt(CPUConfig.prfAddrWidth.W)  // 旧的物理目的寄存器映射（Commit 释放用）
  val ldst          = UInt(5.W)                       // 逻辑目的寄存器编号（= rd，用于 Commit 更新 RRAT）
}

// ---- ROB ↔ Dispatch 4-wide 分配接口 ----
class ROBMultiAllocIO extends Bundle { // 方向以 Dispatch（发送方）为基准
  val availCount = Input(UInt(CPUConfig.robPtrWidth.W))         // ROB 剩余空间
  val request    = Output(UInt(3.W))                            // 请求分配的表项数量（0-4）
  val data       = Output(Vec(4, new ROBAllocData))             // 每条指令的分配数据
  val idxs       = Input(Vec(4, UInt(CPUConfig.robPtrWidth.W))) // ROB 返回的分配指针
}

// ============================================================================
// ROBEntry —— ROB 中每个表项存储的字段
// ============================================================================
// 注意：Store 的地址/数据/掩码信息已移至 StoreBuffer 管理，ROB 仅标记 isStore
class ROBEntry extends ROBAllocData {
  val done         = Bool()     // 该指令是否已执行完毕（Refresh 标记）
  val regWBData    = UInt(32.W) // 写回寄存器的结果（Store 指令为 0，Commit 由 StoreBuffer 实现）
  val actualTaken  = Bool()     // Execute 阶段计算出的实际跳转结果
  val actualTarget = UInt(32.W) // 实际跳转目标
  val mispredict   = Bool()     // 是否预测错误
  val exception    = Bool()     // 是否有异常（预留）
}

// ---- ROB ↔ Refresh 完成标记接口 ----
// Refresh 阶段标记 ROB 表项已执行完毕，写入结果和分支实际信息
// 同时携带 pdst 信息用于 PRF 写入和 ReadyTable 更新
class ROBRefreshIO extends Bundle {
  val valid        = Output(Bool())
  val idx          = Output(UInt(CPUConfig.robPtrWidth.W))    // 完成的 ROB 指针（含回绕位）
  val regWBData    = Output(UInt(32.W))
  val actualTaken  = Output(Bool())
  val actualTarget = Output(UInt(32.W))
  val mispredict   = Output(Bool())
  val pdst         = Output(UInt(CPUConfig.prfAddrWidth.W))   // 物理目的寄存器（PRF 写入 + ReadyTable 标记 ready）
  val regWriteEnable = Output(Bool())                          // 是否需要写回（控制 PRF 写入和 ReadyTable 更新）
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
  val storeSeq     = Output(UInt(CPUConfig.storeSeqWidth.W)) // Store 的 storeSeq（Commit 时传给 SB 定位表项）
  val isBranch     = Output(Bool())     // 用于 BHT 训练（预留）
  val isJump       = Output(Bool())
  val hasCheckpoint = Output(Bool())   // 该指令是否保存了 BCT checkpoint（bType || jalr）
  val mispredict   = Output(Bool())     // 用于调试观测
  val actualTaken  = Output(Bool())
  val actualTarget = Output(UInt(32.W))
  val predictTaken = Output(Bool())
  val bhtMeta      = Output(UInt(2.W))
  val pdst         = Output(UInt(CPUConfig.prfAddrWidth.W))   // 物理目的寄存器（Commit 更新 RRAT 用）
  val stalePdst    = Output(UInt(CPUConfig.prfAddrWidth.W))   // 旧物理目的寄存器（Commit 释放到 FreeList 用）
  val ldst         = Output(UInt(5.W))                         // 逻辑目的寄存器（Commit 更新 RRAT 用）
}

// ==========================================================================
// MemReqBundle —— LSU 对外存储器请求（Load 读 / Store drain 写统一接口）
// ==========================================================================
class MemReqBundle extends Bundle {
  val isWrite = Bool()     // true=写请求（Store drain），false=读请求（Load）
  val addr    = UInt(32.W) // 字节地址（32 位完整地址）
  val wdata   = UInt(32.W) // 写数据（已按字节对齐，仅写请求有效）
  val wstrb   = UInt(4.W)  // 字节写使能掩码（仅写请求有效）
}

// ==========================================================================
// MemRespBundle —— LSU 对外存储器响应
// ==========================================================================
class MemRespBundle extends Bundle {
  val rdata = UInt(32.W)        // 读回数据（原始 32 位字，不做符号扩展）
}
