package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

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
//   7. 被预测分支指令保存 RAT/FreeList/ReadyTable checkpoint
//
// 处理顺序：lane 0 最老 → lane 3 最年轻
// ============================================================================
class Rename extends Module {
  val in  = IO(Flipped(Decoupled(Vec(4, new DecodedInst)))) // 来自 DecRenDff 的 4 条译码结果
  val out = IO(Decoupled(new Rename_Dispatch_Payload))       // 输出到 RenDisDff
  val flush = IO(Input(Bool()))                              // 流水线冲刷信号

  // ---- RAT 接口 ----
  val rat = IO(new Bundle {
    val raddr      = Output(Vec(8, UInt(5.W)))                                // 8 个源操作数读端口
    val rdata      = Input(Vec(8, UInt(CPUConfig.prfAddrWidth.W)))           // 读结果
    val staleRaddr = Output(Vec(4, UInt(5.W)))                                // 4 个 stalePdst 读端口
    val staleRdata = Input(Vec(4, UInt(CPUConfig.prfAddrWidth.W)))           // 读结果
    val wen        = Output(Vec(4, Bool()))                                   // 4 个写端口
    val waddr      = Output(Vec(4, UInt(5.W)))                                // 写地址
    val wdata      = Output(Vec(4, UInt(CPUConfig.prfAddrWidth.W)))          // 写数据
  })

  // ---- FreeList 接口 ----
  val freeList = IO(new Bundle {
    val allocReq  = Output(UInt(3.W))                                        // 请求分配数量
    val canAlloc  = Input(Bool())                                            // 是否有足够空闲
    val allocPdst = Input(Vec(4, UInt(CPUConfig.prfAddrWidth.W)))           // 分配的物理寄存器
    val doAlloc   = Output(Bool())                                           // 确认分配
  })

  // ---- ReadyTable 接口 ----
  val readyTable = IO(new Bundle {
    val busyVen  = Output(Vec(4, Bool()))                                    // 置 busy 使能
    val busyAddr = Output(Vec(4, UInt(CPUConfig.prfAddrWidth.W)))           // 置 busy 地址
  })

  // ---- BranchCheckpoint 接口 ----
  val checkpoint = IO(new Bundle {
    // ---- 第一个 checkpoint（到第一个被预测分支为止）----
    val saveValid = Output(Bool())                                           // 第一个 checkpoint 保存使能
    val saveIdx   = Input(UInt(CPUConfig.ckptPtrWidth.W))                    // 第一个 checkpoint 全指针
    val canSave1  = Input(Bool())   // BCT 至少有 1 个空位
    val canSave2  = Input(Bool())   // BCT 至少有 2 个空位
    val ckptLaneMask  = Output(Vec(4, Bool()))  // 各 lane 是否包含在第一个 checkpoint RAT 中
    val ckptAllocCount = Output(UInt(3.W))      // 第一个 checkpoint 中需要计入的 FreeList 分配数量

    // ---- 第二个 checkpoint（到第二个被预测分支为止）----
    val saveValid2 = Output(Bool())                                          // 第二个 checkpoint 保存使能
    val saveIdx2   = Input(UInt(CPUConfig.ckptPtrWidth.W))                   // 第二个 checkpoint 全指针
    val ckptLaneMask2  = Output(Vec(4, Bool()))  // 各 lane 是否包含在第二个 checkpoint RAT 中
    val ckptAllocCount2 = Output(UInt(3.W))      // 第二个 checkpoint 中需要计入的 FreeList 分配数量
  })

  // ---- 逐路处理：提取指令字段 ----
  val rds   = Wire(Vec(4, UInt(5.W)))    // 每条指令的 rd
  val rs1s  = Wire(Vec(4, UInt(5.W)))    // 每条指令的 rs1
  val rs2s  = Wire(Vec(4, UInt(5.W)))    // 每条指令的 rs2
  val regWriteEnables = Wire(Vec(4, Bool())) // 是否写回寄存器
  val memWriteEnables = Wire(Vec(4, Bool())) // 是否 Store

  // 检测本拍是否有被预测分支（用于 checkpoint）
  val hasPredictedBranch = Wire(Bool())
  val predictedBranchVec = Wire(Vec(4, Bool()))

  for (i <- 0 until 4) {
    val td    = in.bits(i).type_decode_together
    val uType = td(8)
    val jal   = td(7)
    val jalr  = td(6)
    val bType = td(5)
    val lType = td(4)
    val iType = td(3)
    val sType = td(2)
    val rType = td(1)

    rds(i)  := in.bits(i).inst(11, 7)
    rs1s(i) := in.bits(i).inst(19, 15)
    rs2s(i) := in.bits(i).inst(24, 20)
    regWriteEnables(i) := uType || jal || jalr || lType || iType || rType
    memWriteEnables(i) := sType

    // 被预测分支：B型 或 JALR（JALR 总是标记为 mispredict 跳转，需要 checkpoint）
    predictedBranchVec(i) := in.bits(i).valid && (bType || jalr)
  }
  hasPredictedBranch := predictedBranchVec.asUInt.orR

  // 标记第一个被预测分支（仅该指令在 ROB 中设置 hasCheckpoint，用于 Commit 时释放 BCT 表项）
  val firstPredictedBranch = Wire(Vec(4, Bool()))
  firstPredictedBranch(0) := predictedBranchVec(0)
  for (i <- 1 until 4) {
    firstPredictedBranch(i) := predictedBranchVec(i) && !predictedBranchVec.take(i).reduce(_ || _)
  }

  // 标记第二个被预测分支（同组内有两个分支时，第二个分支也需要 checkpoint）
  // 例：lane 0 和 lane 3 都是分支 → firstPredictedBranch=[1,0,0,0], secondPredictedBranch=[0,0,0,1]
  val secondPredictedBranch = Wire(Vec(4, Bool()))
  secondPredictedBranch(0) := false.B  // lane 0 不可能是第二个
  for (i <- 1 until 4) {
    // 第二个被预测分支：自身是分支，且之前恰好有一个分支（第一个已标记）
    val branchCountBefore = PopCount(predictedBranchVec.take(i))
    secondPredictedBranch(i) := predictedBranchVec(i) && branchCountBefore === 1.U
  }
  val hasSecondPredictedBranch = secondPredictedBranch.asUInt.orR

  // ---- RAT 读取源操作数映射（从 RAT 读取上一拍末尾的映射）----
  for (i <- 0 until 4) {
    rat.raddr(i * 2)     := rs1s(i)
    rat.raddr(i * 2 + 1) := rs2s(i)
    rat.staleRaddr(i)    := rds(i)
  }

  // ---- Rename Bypass 逻辑 ----
  // 对于同拍内更老的指令如果写了同一逻辑寄存器 rd，需要使用其 pdst 作为 bypass
  // 构建按 lane 从老到年轻的临时 RAT 视图

  // 计算每路实际需要分配的数量和映射
  val needAlloc = Wire(Vec(4, Bool())) // 每条指令是否需要从 FreeList 分配 pdst
  val allocOffsets = Wire(Vec(4, UInt(3.W))) // 在 FreeList 分配结果中的偏移

  // 计算分配偏移：只对 valid && regWriteEnable && rd != x0 的指令分配
  for (i <- 0 until 4) {
    needAlloc(i) := in.bits(i).valid && regWriteEnables(i) && rds(i) =/= 0.U
  }
  allocOffsets(0) := 0.U
  for (i <- 1 until 4) {
    allocOffsets(i) := allocOffsets(i - 1) + Mux(needAlloc(i - 1), 1.U, 0.U)
  }
  val totalAllocCount = allocOffsets(3) + Mux(needAlloc(3), 1.U, 0.U)

  // 每路分配的 pdst
  val pdsts = Wire(Vec(4, UInt(CPUConfig.prfAddrWidth.W)))
  for (i <- 0 until 4) {
    pdsts(i) := Mux(needAlloc(i), freeList.allocPdst(allocOffsets(i)), 0.U)
  }

  // ---- Rename Bypass：源操作数映射 ----
  // psrc 的来源优先级（从高到低）：
  //   1. 同拍更老 lane 的 pdst（rename bypass）
  //   2. RAT 读取的映射（上一拍末尾值）
  val psrc1s = Wire(Vec(4, UInt(CPUConfig.prfAddrWidth.W)))
  val psrc2s = Wire(Vec(4, UInt(CPUConfig.prfAddrWidth.W)))

  for (i <- 0 until 4) {
    // rs1 的 bypass 检查：从最近的更老 lane (i-1) 到最远 (0)
    psrc1s(i) := rat.rdata(i * 2) // 默认从 RAT 读取
    for (j <- 0 until i) {
      // 如果更老的 lane j 写了同一逻辑寄存器，使用其 pdst
      when(needAlloc(j) && rds(j) === rs1s(i) && rs1s(i) =/= 0.U) {
        psrc1s(i) := pdsts(j)
      }
    }
    // x0 永远映射到 p0
    when(rs1s(i) === 0.U) { psrc1s(i) := 0.U }

    // rs2 的 bypass 检查
    psrc2s(i) := rat.rdata(i * 2 + 1)
    for (j <- 0 until i) {
      when(needAlloc(j) && rds(j) === rs2s(i) && rs2s(i) =/= 0.U) {
        psrc2s(i) := pdsts(j)
      }
    }
    when(rs2s(i) === 0.U) { psrc2s(i) := 0.U }
  }

  // ---- stalePdst Bypass ----
  // stalePdst = 当前 RAT[rd] 在分配 pdst 之前的值
  // 也需要考虑同拍更老 lane 是否已经更新了 RAT[rd]
  val stalePdsts = Wire(Vec(4, UInt(CPUConfig.prfAddrWidth.W)))
  for (i <- 0 until 4) {
    stalePdsts(i) := rat.staleRdata(i) // 默认从 RAT 读取
    for (j <- 0 until i) {
      when(needAlloc(j) && rds(j) === rds(i) && rds(i) =/= 0.U) {
        stalePdsts(i) := pdsts(j)
      }
    }
    when(rds(i) === 0.U) { stalePdsts(i) := 0.U }
  }

  // ---- 判断是否能进行 Rename ----
  // 如果本拍有 2 个被预测分支，需要 BCT 有 2 个空位（canSave2）
  // 如果只有 1 个被预测分支，只需要 1 个空位（canSave1）
  // 没有分支则不需要 BCT 空位
  val ckptReady = Mux(hasSecondPredictedBranch, checkpoint.canSave2,
                    Mux(hasPredictedBranch, checkpoint.canSave1, true.B))
  val canRename = freeList.canAlloc && ckptReady && out.ready
  val doRename  = in.valid && canRename && !flush

  // ---- FreeList 分配请求 ----
  freeList.allocReq := Mux(doRename, totalAllocCount, 0.U)
  freeList.doAlloc  := doRename

  // ---- RAT 写入（更新映射表）----
  for (i <- 0 until 4) {
    rat.wen(i)   := doRename && needAlloc(i)
    rat.waddr(i) := rds(i)
    rat.wdata(i) := pdsts(i)
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
  checkpoint.saveValid  := doRename && hasPredictedBranch
  checkpoint.saveValid2 := doRename && hasSecondPredictedBranch

  // ---- Checkpoint Lane Mask 计算（第一个 checkpoint）----
  // 第一个 checkpoint RAT 应只包含 lane 0 到第一个被预测分支（含）的写入
  // hadBranchBefore(i) 表示在 lane i 之前（不含 i）是否已经出现过被预测分支
  val hadBranchBefore = Wire(Vec(4, Bool()))
  hadBranchBefore(0) := false.B
  for (i <- 1 until 4) {
    hadBranchBefore(i) := predictedBranchVec.take(i).reduce(_ || _)
  }
  // ckptLaneMask(i) = lane i 在第一个被预测分支及之前 → 应包含在第一个 checkpoint 中
  for (i <- 0 until 4) {
    checkpoint.ckptLaneMask(i) := !hadBranchBefore(i)
  }
  // ckptAllocCount：仅统计第一个 checkpoint 包含的 lane 中的分配数量
  checkpoint.ckptAllocCount := PopCount(
    (0 until 4).map(i => needAlloc(i) && !hadBranchBefore(i))
  )

  // ---- Checkpoint Lane Mask 计算（第二个 checkpoint）----
  // 第二个 checkpoint RAT 包含 lane 0 到第二个被预测分支（含）的写入
  // hadTwoBranchesBefore(i) = lane i 之前（不含 i）是否已有 >= 2 个被预测分支
  val hadTwoBranchesBefore = Wire(Vec(4, Bool()))
  hadTwoBranchesBefore(0) := false.B
  for (i <- 1 until 4) {
    hadTwoBranchesBefore(i) := PopCount(predictedBranchVec.take(i)) >= 2.U
  }
  // ckptLaneMask2(i) = lane i 在第二个被预测分支及之前 → 应包含在第二个 checkpoint 中
  for (i <- 0 until 4) {
    checkpoint.ckptLaneMask2(i) := !hadTwoBranchesBefore(i)
  }
  // ckptAllocCount2：统计第二个 checkpoint 包含的 lane 中的分配数量
  checkpoint.ckptAllocCount2 := PopCount(
    (0 until 4).map(i => needAlloc(i) && !hadTwoBranchesBefore(i))
  )

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
    out.bits.entries(i).memWriteEnable       := memWriteEnables(i)
    out.bits.entries(i).valid                := in.bits(i).valid
    out.bits.entries(i).psrc1                := psrc1s(i)
    out.bits.entries(i).psrc2                := psrc2s(i)
    out.bits.entries(i).pdst                 := pdsts(i)
    out.bits.entries(i).stalePdst            := stalePdsts(i)
    out.bits.entries(i).ldst                 := rds(i)
    // 分支 checkpoint 索引：第一个被预测分支使用 saveIdx，第二个使用 saveIdx2
    out.bits.entries(i).checkpointIdx         := Mux(firstPredictedBranch(i), checkpoint.saveIdx,
                                                   Mux(secondPredictedBranch(i), checkpoint.saveIdx2, 0.U))
    // hasCheckpoint：第一个和第二个被预测分支都为 true（各自对应不同的 BCT 表项）
    out.bits.entries(i).hasCheckpoint          := firstPredictedBranch(i) || secondPredictedBranch(i)
  }
  out.bits.validCount := PopCount(in.bits.map(_.valid))
  out.bits.storeCount := PopCount((in.bits.map(_.valid) zip memWriteEnables).map {
    case (valid, memWen) => valid && memWen
  })

  // ---- 握手信号 ----
  in.ready  := canRename
  out.valid := doRename
}
