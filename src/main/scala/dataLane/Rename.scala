package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig
import mycpu.device.RATReadWriteIO
import mycpu.device.RenameIO
import chisel3.{UIntIntf => i}
import mycpu.device.saveRequestRename

// ============================================================================
// Rename（重命名阶段）—— 4-wide 寄存器重命名
// ============================================================================
// 完成逻辑寄存器到物理寄存器的映射：
//   1. 从 RAT 查询每条指令的源操作数 rs1/rs2 对应的物理寄存器编号 psrc1/psrc2
//   2. 同拍更老指令若更新了相同逻辑寄存器，需通过 rename bypass 获取新映射
//   3. 若指令写目的寄存器且 rd != x0，从 FreeList 分配新的 pdst
//   4. 保存旧映射 stalePdst = RAT[rd]（考虑同拍 bypass）到 ROB
//   5. 更新 RAT 映射，使更年轻 lane 可见
//   6. 新分配的 pdst 在 ReadyTable 中置 busy
//   7. 快照型分支指令需保存 RAT/FreeList/ReadyTable checkpoint
//   ※ 快照型分支：B型 或 JALR（JALR 总是标记为 mispredict 跳转，需要 checkpoint，JAL 一定跳转，无需 checkpoint）
// 处理顺序：lane 0 最老 → lane 3 最年轻
// ============================================================================
class Rename extends Module {
  val in  = IO(Flipped(Decoupled(Vec(4, new DecodedInst)))) // 来自 DecRenDff 的 4 条译码结果
  val out = IO(Decoupled(new Rename_Dispatch_Payload))      // 输出到 RenDisDff
  val flush = IO(Input(Bool()))                             // 流水线冲刷信号

  // ---- RAT 接口 ----
  val rat = IO(new RATReadWriteIO)
  // ---- FreeList 接口 ----
  val freeList = IO(new RenameIO)
  // ---- ReadyTable 接口 ----
  val readyTable = IO(new Bundle {
    val busyVen  = Output(Vec(4, Bool()))                         // 置 busy 使能
    val busyAddr = Output(Vec(4, UInt(CPUConfig.prfAddrWidth.W))) // 置 busy 地址
  })

  // ---- BranchCheckpoint 接口 ----
  val ckPointReq = IO(new saveRequestRename)
  val ckptRAT = IO(new Bundle {
    val postRename1 = Output(Vec(32, UInt(CPUConfig.prfAddrWidth.W))) // RAT 快照 1
    val postRename2 = Output(Vec(32, UInt(CPUConfig.prfAddrWidth.W))) // RAT 快照 2
  })

  // ---- 逐路处理：提取指令字段 ----
  val rs1s = in.bits.map(_.inst(19, 15)) // 每条指令的 rs1
  val rs2s = in.bits.map(_.inst(24, 20)) // 每条指令的 rs2
  val rds  = in.bits.map(_.inst(11, 7))  // 每条指令的 rd
  val regWriteEnables = in.bits.map(_.regWriteEnable) // 是否写回寄存器
  val memWriteEnables = in.bits.map(_.type_decode_together(2)) // sType

  val predictedBranchVec = Wire(Vec(4, Bool())) // 标记每个槽位是否是快照型分支
  for (i <- 0 until 4) {
    val td    = in.bits(i).type_decode_together
    val jalr  = td(6)
    val bType = td(5)
    predictedBranchVec(i) := in.bits(i).valid && (bType || jalr) // 快照型分支即 bType 和 JALR
  }
  val firstPredictedBranchUInt = PriorityEncoderOH(predictedBranchVec.asUInt) // 第一个快照型分支
  val secondPredictedBranchUInt = PriorityEncoderOH(predictedBranchVec.asUInt & ~firstPredictedBranchUInt) // 第二个快照型分支：掩去第一个后再找
  val hasFirstPredictedBranch = firstPredictedBranchUInt.orR // 是否有一个预测分支
  val hasSecondPredictedBranch = secondPredictedBranchUInt.orR // 是否有第2个预测分支
  // 后面仍然希望用 Vec[Bool]
  val firstPredictedBranch  = VecInit(firstPredictedBranchUInt.asBools)
  val secondPredictedBranch = VecInit(secondPredictedBranchUInt.asBools)

  // ---- 判断是否能进行 Rename ----
  val ckptReady = MuxCase(true.B, Seq( // 没有分支则不需要 BCT 空位，直接为 ready
    hasSecondPredictedBranch -> ckPointReq.canSave2, // 如果本拍有 2 个被预测分支，需要 BCT 有 2 个空位（canSave2）
    hasFirstPredictedBranch -> ckPointReq.canSave1 // 如果只有 1 个被预测分支，只需要 1 个空位（canSave1）
  ))
  val canRename = freeList.canAlloc && ckptReady && out.ready
  val doRename  = in.valid && canRename && !flush

  // 计算每路实际需要分配的槽位使能 needAlloc 和映射偏移量 allocOffsets
  val needAlloc = Wire(Vec(4, Bool())) // 每条指令是否需要从 FreeList 分配 pdst
  for (i <- 0 until 4) {
    needAlloc(i) := in.bits(i).valid && regWriteEnables(i) && rds(i) =/= 0.U
  }
  val allocOffsets = WireInit(VecInit(Seq.fill(4)(0.U(2.W)))) // 在 FreeList 分配结果中的偏移
  for (i <- 1 until 4) {
    allocOffsets(i) := allocOffsets(i - 1) + Mux(needAlloc(i - 1), 1.U, 0.U)
  }
  // 使用 +& (宽化加法) 避免当 4 路全部需要分配时 3+1=4 溢出截断为 0
  val totalAllocCount = allocOffsets(3) +& Mux(needAlloc(3), 1.U, 0.U)

  freeList.doAlloc  := doRename // 向 freeList 提交是否执行了分配操作使能
  freeList.allocReq := Mux(doRename, totalAllocCount, 0.U) // 请求分配的个数

  // 按照偏移提取出每路分配的 pdst
  val pdsts = Wire(Vec(4, UInt(CPUConfig.prfAddrWidth.W)))
  for (i <- 0 until 4) {
    pdsts(i) := Mux(needAlloc(i), freeList.allocPdst(allocOffsets(i)), 0.U)
  }

  // ---- RAT 读取和写入源操作数映射和 Rename Bypass ----
  // psrc 的来源优先级（从高到低）：
  //   1. 同拍更老 lane 的 pdst（rename bypass），因为此时还没写入 RAT 会读出旧值
  //   2. RAT 读取的映射（上一拍末尾值）
  // stalePdst = 当前 RAT[rd] 在分配 pdst 之前的值 也需要考虑同拍更老 lane 是否已经更新了 RAT[rd]
  for (i <- 0 until 4) {
    rat.raddr(i * 2)     := rs1s(i)
    rat.raddr(i * 2 + 1) := rs2s(i)
    rat.staleRaddr(i)    := rds(i)
    rat.wen(i)   := doRename && needAlloc(i)
    rat.waddr(i) := rds(i)
    rat.wdata(i) := pdsts(i)
  }
  val psrc1s     = Wire(Vec(4, UInt(CPUConfig.prfAddrWidth.W)))
  val psrc2s     = Wire(Vec(4, UInt(CPUConfig.prfAddrWidth.W)))
  val stalePdsts = Wire(Vec(4, UInt(CPUConfig.prfAddrWidth.W)))
  for (i <- 0 until 4) {
    psrc1s(i) := rat.rdata(i * 2)
    psrc2s(i) := rat.rdata(i * 2 + 1)
    stalePdsts(i) := rat.staleRdata(i)
    for (j <- 0 until i) { // 如果更老的 lane j 写了同一逻辑寄存器，使用其 pdst
      when(needAlloc(j) && rds(j) === rs1s(i) && rs1s(i) =/= 0.U) { psrc1s(i) := pdsts(j) }
      when(needAlloc(j) && rds(j) === rs2s(i) && rs2s(i) =/= 0.U) { psrc2s(i) := pdsts(j) }
      when(needAlloc(j) && rds(j) === rds(i) && rds(i) =/= 0.U) { stalePdsts(i) := pdsts(j) }
    }
    when(rs1s(i) === 0.U) { psrc1s(i) := 0.U } // x0 永远映射到 p0
    when(rs2s(i) === 0.U) { psrc2s(i) := 0.U }
    when(rds(i) === 0.U) { stalePdsts(i) := 0.U }
  }

  // ---- ReadyTable 置 busy（新分配的 pdst 还没有执行结果）----
  for (i <- 0 until 4) {
    readyTable.busyVen(i)  := doRename && needAlloc(i)
    readyTable.busyAddr(i) := pdsts(i)
  }

  // ---- Branch Checkpoint 保存 ----
  // 如果本拍有被预测分支，保存当前 RAT/FreeList/ReadyTable 状态
  // 注意：checkpoint 保存的是本拍 Rename 写入之前还是之后的状态？
  // 应保存 Rename 写入之后的 RAT 状态（因为被预测分支本身可能也写 rd）
  // 但 FreeList 和 ReadyTable 的 checkpoint 由 myCPU 顶层在 Rename 完成后保存
  ckPointReq.saveValid1  := doRename && hasFirstPredictedBranch
  ckPointReq.saveValid2 := doRename && hasSecondPredictedBranch

  private def ohToInclusiveLaneMask(oh: UInt, width: Int): UInt = {
    // 例子（width = 4）:
    // oh = 0001 -> 0001
    // oh = 0010 -> 0011
    // oh = 0100 -> 0111
    // oh = 1000 -> 1111
    // oh = 0000 -> 1111
    (Cat(oh, 0.U(1.W)) - 1.U)((width - 1), 0)
  }
  // ---- Checkpoint Lane Mask 计算（第一个 checkpoint）----
  // 第一个 checkpoint RAT 应只包含 lane 0 到第一个被预测分支（含）的写入
  // ckptLaneMask1(i) = lane i 在第一个被预测分支及之前 → 应包含在第一个 checkpoint 中
  val ckptLaneMask1 = ohToInclusiveLaneMask(firstPredictedBranchUInt, 4)
  val ckptLaneMask1Vec = VecInit(ckptLaneMask1.asBools)
  // ckptAllocCount：仅统计第一个 checkpoint 包含的 lane 中的分配数量
  freeList.snapAllocReq1 := PopCount(needAlloc.asUInt & ckptLaneMask1)

  // ---- Checkpoint Lane Mask 计算（第二个 checkpoint）----
  // 第二个 checkpoint RAT 包含 lane 0 到第二个被预测分支（含）的写入
  // ckptLaneMask2(i) = lane i 在第二个被预测分支及之前 → 应包含在第二个 checkpoint 中
  val ckptLaneMask2 = ohToInclusiveLaneMask(secondPredictedBranchUInt, 4)
  val ckptLaneMask2Vec = VecInit(ckptLaneMask2.asBools)
  // ckptAllocCount2：统计第二个 checkpoint 包含的 lane 中的分配数量
  freeList.snapAllocReq2 := PopCount(needAlloc.asUInt & ckptLaneMask2)

  // ---- 第一个 Checkpoint RAT 快照构建 ----
  // 只叠加 ckptLaneMask1 为 true 的 lane 的写入（到第一个被预测分支为止）
  val postRenameRAT1 = WireInit(rat.snapData)
  for (i <- 0 until 4) {
    when(ckptLaneMask1Vec(i) && rat.wen(i) && rat.waddr(i) =/= 0.U) {
      postRenameRAT1(rat.waddr(i)) := rat.wdata(i)
    }
  }
  ckptRAT.postRename1 := postRenameRAT1

  // ---- 第二个 Checkpoint RAT 快照构建 ----
  // 叠加 ckptLaneMask2 为 true 的 lane 的写入（到第二个被预测分支为止）
  val postRenameRAT2 = WireInit(rat.snapData)
  for (i <- 0 until 4) {
    when(ckptLaneMask2Vec(i) && rat.wen(i) && rat.waddr(i) =/= 0.U) {
      postRenameRAT2(rat.waddr(i)) := rat.wdata(i)
    }
  }
  ckptRAT.postRename2 := postRenameRAT2

  // ---- 向下游输出 RenameEntry ----
  for (i <- 0 until 4) {
    out.bits.entries(i).pc                   := in.bits(i).pc
    out.bits.entries(i).inst                 := in.bits(i).inst
    out.bits.entries(i).imm                  := in.bits(i).imm
    out.bits.entries(i).type_decode_together := in.bits(i).type_decode_together
    out.bits.entries(i).predict_taken        := in.bits(i).predict_taken
    out.bits.entries(i).predict_target       := in.bits(i).predict_target
    out.bits.entries(i).bht_meta             := in.bits(i).bht_meta
    out.bits.entries(i).regWriteEnable       := regWriteEnables(i)
    out.bits.entries(i).valid                := in.bits(i).valid
    out.bits.entries(i).psrc1                := psrc1s(i)
    out.bits.entries(i).psrc2                := psrc2s(i)
    out.bits.entries(i).pdst                 := pdsts(i)
    out.bits.entries(i).stalePdst            := stalePdsts(i)
    out.bits.entries(i).ldst                 := rds(i)
    // 分支 checkpoint 索引：第一个被预测分支使用 saveIdx，第二个使用 saveIdx2
    out.bits.entries(i).checkpointIdx        := Mux(firstPredictedBranch(i), ckPointReq.saveIdx1,
                                                  Mux(secondPredictedBranch(i), ckPointReq.saveIdx2, 0.U))
    // hasCheckpoint：第一个和第二个被预测分支都为 true（各自对应不同的 BCT 表项）
    out.bits.entries(i).hasCheckpoint        := firstPredictedBranch(i) || secondPredictedBranch(i)
  }
  out.bits.validCount := PopCount(in.bits.map(_.valid))
  out.bits.storeCount := PopCount((in.bits.map(_.valid) zip memWriteEnables).map {
    case (valid, memWen) => valid && memWen
  })

  // ---- 握手信号 ----
  in.ready  := canRename
  out.valid := doRename
}
