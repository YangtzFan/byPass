package mycpu

import chisel3.util.log2Ceil

// CPU 全局参数配置
// 修改此文件中的参数即可切换不同的硬件生成方案

object CPUConfig {
  // ---- 分支预测策略 ----
  sealed trait BranchPredictorType
  case object StaticBTFN extends BranchPredictorType   // 静态 BTFN (Backward Taken, Forward Not-taken)
  case object DynamicBHT extends BranchPredictorType   // 动态 2-bit 饱和计数器 (格雷码编码)
  val branchPredictor: BranchPredictorType = DynamicBHT // 当前使用的分支预测策略（修改这里切换）

  // ---- BHT 参数（仅 DynamicBHT 时有效）----
  sealed trait BHTSize { val entries: Int }
  case object BHT64  extends BHTSize { val entries = 64 }   // PC[7:2] 索引
  case object BHT128 extends BHTSize { val entries = 128 }  // PC[8:2] 索引
  case object BHT256 extends BHTSize { val entries = 256 }  // PC[9:2] 索引
  val bhtSize: BHTSize = BHT64 // 当前 BHT 表项大小（修改这里切换）
  val bhtEntries: Int = bhtSize.entries

  // ---- ROB 参数 ----
  val robEntries: Int = 128 // ROB 表项数
  val robIdxWidth: Int = log2Ceil(robEntries) // ROB 索引位宽（7 位）
  val robPtrWidth: Int = robIdxWidth + 1      // ROB 指针位宽（含回绕位，8 位）

  // ---- FetchBuffer 参数 ----
  val fetchBufferEntries: Int = 16 // FetchBuffer 容量

  // ---- Commit 参数 ----
  // 提交宽度：一拍内 ROB 最多按序提交几条指令。
  // 过渡期暂定 1；后续做完“乱序发射 + 双宽 retire”后会提升到 2。
  // difftest 验证框架已按 Vec 接口接入，此处修改即可自动扩展验证路径。
  val commitWidth: Int = 1

  // ---- OoO 后端宽度参数（TASK 3.1 草拟；阶段 1-2 全部保持 1，后续阶段再向上翻）----
  // 这些参数从“过渡期默认 1”起步，所有下游模块都按 Vec(width, T) 接口铺管；
  // 扩展到 2 / 4 发射时只需修改这里，并在对应阶段打开 Vec 内部的并行逻辑。
  val issueWidth:    Int = 1                      // 每拍 Issue 发射数（= Vec 宽度）
  val executeWidth:  Int = issueWidth             // 每拍 Execute 执行数（约束：≥ issueWidth）
  val memoryWidth:   Int = issueWidth             // 每拍 Memory 处理数（≥ 发访存指令的 lane 数）
  val refreshWidth:  Int = executeWidth           // 每拍 Refresh 写回数（= executeWidth）
  val prfReadPorts:  Int = 2 * issueWidth         // PRF 读口数（每 lane 2 个源操作数）
  val prfWritePorts: Int = refreshWidth           // PRF 写口数（= refreshWidth）

  // ---- IssueQueue 参数 ----
  val issueQueueEntries: Int = 16  // IssueQueue 容量
  val iqIdxWidth: Int = log2Ceil(issueQueueEntries)  // IssueQueue 物理槽位索引位宽（4 位）
  // instSeq 采用循环序号 + 减法最高位比较（与 StoreBuffer 的 storeSeq 同构）。
  // 只要活跃条目数（≤ issueQueueEntries = 16）远小于半区间 2^(instSeqWidth-1)，
  // 比较即正确；这里取 8 位，半区间 128 >> 16，留足余量且与 storeSeqWidth 保持一致。
  val instSeqWidth: Int = 8

  // ---- StoreBuffer 参数 ----
  val sbEntries: Int = 32                   // StoreBuffer 深度
  val sbIdxWidth: Int = log2Ceil(sbEntries) // StoreBuffer 索引位宽（5 位）
  val storeSeqWidth: Int = 8                // storeSeq 逻辑年龄位宽（8 位，使用循环比较处理回绕，半区间 128 > sbEntries 故安全）
  val axiSqEntries: Int = 16                // AXIStoreQueue 深度：保存已提交但尚未写回 DRAM 的 store
  val axiSqStoreBurstLimit: Int = 8         // 在有 load miss 等待时，最多连续优先处理的 store 数量，避免 load 饥饿

  // ---- PRF（物理寄存器堆）参数 ----
  val prfEntries: Int = 128                    // 物理寄存器数量（p0~p127）
  val prfAddrWidth: Int = log2Ceil(prfEntries) // 物理寄存器编号位宽（7 位）
  val renameWidth: Int = 4                     // 重命名宽度（每拍最多处理 4 条指令）
  // ---- FreeList 参数 ----
  val freeListEntries: Int = prfEntries - 32   // FreeList 容量（96）

  // ---- 分支 Checkpoint 参数 ----
  val maxBranchCheckpoints: Int = 8                      // 最大同时在飞分支数（checkpoint 数量）
  val ckptIdxWidth: Int = log2Ceil(maxBranchCheckpoints) // checkpoint 索引位宽（3 位）
  val ckptPtrWidth: Int = ckptIdxWidth + 1               // checkpoint 全指针位宽（含回绕位，4 位）

  // ---- 便捷方法 ----
  def useBHT: Boolean  = branchPredictor == DynamicBHT
  def useBTFN: Boolean = branchPredictor == StaticBTFN
}
