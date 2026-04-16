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

  // ---- IssueQueue 参数 ----
  val issueQueueEntries: Int = 16  // IssueQueue 容量
  val iqIdxWidth: Int = log2Ceil(issueQueueEntries)  // IssueQueue 物理槽位索引位宽（4 位）
  val instSeqWidth: Int = 32       // instSeq 指令逻辑年龄位宽（32 位，避免回绕）

  // ---- StoreBuffer 参数 ----
  val sbEntries: Int = 32                   // StoreBuffer 深度
  val sbIdxWidth: Int = log2Ceil(sbEntries) // StoreBuffer 索引位宽（5 位）
  val sbPtrWidth: Int = sbIdxWidth + 1      // StoreBuffer 指针位宽（含回绕位，6 位）——保留兼容
  val storeSeqWidth: Int = 32               // storeSeq 逻辑年龄位宽（使用 32 位避免回绕导致的比较错误）

  // ---- 便捷方法 ----
  def useBHT: Boolean  = branchPredictor == DynamicBHT
  def useBTFN: Boolean = branchPredictor == StaticBTFN
}
