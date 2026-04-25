package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig
import mycpu.device.ALU

// ============================================================================
// Execute（执行阶段）—— 双 lane 版本
// ============================================================================
// - lane0：全功能（ALU + 分支验证 + BHT 更新 + Load/Store 地址计算）
// - lane1：仅 ALU（iType/rType/lui）；分支/访存/JALR 不会路由到 lane1
//
// 旁路网络：旁路源扩为 Vec，因为 Memory/Refresh/Commit 三级每拍可能各有多条指令
// 产生结果（最多 memoryWidth/refreshWidth/commitWidth 条）：
//   - mem:    Vec(memoryWidth,  {pdst, data, wen})
//   - ref:    Vec(refreshWidth, {pdst, data, wen})
//   - commit: Vec(commitWidth,  {pdst, data, wen})
// 优先级：mem > ref > commit > PRF 兜底。同级内按 Vec 索引 0,1,... 顺序。
// ============================================================================
class Execute extends Module {
  val in = IO(Flipped(Decoupled(new ReadReg_Execute_Payload)))
  val out = IO(Decoupled(new Execute_Memory_Payload))

  // ---- 数据旁路转发 Vec 端口 ----
  val fwd = IO(new Bundle {
    val mem_pdst    = Input(Vec(CPUConfig.memoryWidth,  UInt(CPUConfig.prfAddrWidth.W)))
    val mem_data    = Input(Vec(CPUConfig.memoryWidth,  UInt(32.W)))
    val mem_wen     = Input(Vec(CPUConfig.memoryWidth,  Bool()))
    val ref_pdst    = Input(Vec(CPUConfig.refreshWidth, UInt(CPUConfig.prfAddrWidth.W)))
    val ref_data    = Input(Vec(CPUConfig.refreshWidth, UInt(32.W)))
    val ref_wen     = Input(Vec(CPUConfig.refreshWidth, Bool()))
    val commit_pdst = Input(Vec(CPUConfig.commitWidth,  UInt(CPUConfig.prfAddrWidth.W)))
    val commit_data = Input(Vec(CPUConfig.commitWidth,  UInt(32.W)))
    val commit_wen  = Input(Vec(CPUConfig.commitWidth,  Bool()))
  })

  // BHT 更新：只有 lane0 会产生分支
  private val bhtIdxWidth = log2Ceil(CPUConfig.bhtEntries)
  val bht_update = Option.when(CPUConfig.useBHT){IO(new Bundle {
    val valid = Output(Bool())
    val idx   = Output(UInt(bhtIdxWidth.W))
    val taken = Output(Bool())
  })}

  // BTB 更新：只有 lane0 可能是 JALR
  val btb_update = Option.when(CPUConfig.useBTB){IO(new Bundle {
    val valid  = Output(Bool())
    val pc     = Output(UInt(32.W))
    val target = Output(UInt(32.W))
  })}

  out.bits := DontCare

  // 旁路匹配辅助函数：给定物理源寄存器编号 psrc，返回旁路后的数据
  private def bypass(psrc: UInt, fallback: UInt): UInt = {
    // mem 级 Vec
    val memCases = (0 until CPUConfig.memoryWidth).map { k =>
      (fwd.mem_wen(k) && (fwd.mem_pdst(k) =/= 0.U) && (fwd.mem_pdst(k) === psrc)) -> fwd.mem_data(k)
    }
    val refCases = (0 until CPUConfig.refreshWidth).map { k =>
      (fwd.ref_wen(k) && (fwd.ref_pdst(k) =/= 0.U) && (fwd.ref_pdst(k) === psrc)) -> fwd.ref_data(k)
    }
    val commitCases = (0 until CPUConfig.commitWidth).map { k =>
      (fwd.commit_wen(k) && (fwd.commit_pdst(k) =/= 0.U) && (fwd.commit_pdst(k) === psrc)) -> fwd.commit_data(k)
    }
    PriorityMux(memCases ++ refCases ++ commitCases :+ (true.B -> fallback))
  }

  // ============================================================================
  // 【阶段 A1】同拍 lane0 → lane(>=1) 前递（EX-in-EX forwarding）
  // ----------------------------------------------------------------------------
  // 背景：当 lane1 发射的指令同拍消费 lane0 的写回目的物理寄存器时，
  //      在不加入本前递前，Issue 阶段会触发 pairRawConflict 禁 pair，
  //      把本来可以双发射的 ALU-依赖对退化为单发射（IPC 上限被压到 1）。
  // 方案：对于 ALU / LUI / AUIPC / JAL / JALR 这类"在 Execute 本拍即可算出结果"
  //      的指令，lane0 的 `out.bits.lanes(0).data` 就是本拍的最终值，可直接
  //      组合旁路给同拍的 lane1+ 源操作数 mux。Load（lType）类指令的结果
  //      要等到下一拍 Memory 阶段才产出，无法同拍前递，因此 lane0 为 Load
  //      时仍需在 Issue 阶段禁 pair（由 Issue.scala 的 pairRawConflict 负责）。
  // 实现：下面的 lane0CanForward/lane0Pdst/lane0Data 由 lane0 迭代赋值（见 for
  //      循环体尾部），然后 lane>=1 的 actual_rdata1/2 会优先使用它。
  // ============================================================================
  val lane0CanForward = WireInit(false.B)
  val lane0FwdPdst    = WireInit(0.U(CPUConfig.prfAddrWidth.W))
  val lane0FwdData    = WireInit(0.U(32.W))

  // ---- 每 lane 独立算一遍 ALU ----
  for (k <- 0 until CPUConfig.issueWidth) {
    val inL  = in.bits.lanes(k)
    val outL = out.bits.lanes(k)

    val funct3 = inL.inst(14, 12)
    val psrc1  = inL.psrc1
    val psrc2  = inL.psrc2
    val pdst   = inL.pdst

    val uType = inL.type_decode_together(8)
    val jal   = inL.type_decode_together(7)
    val jalr  = inL.type_decode_together(6)
    val bType = inL.type_decode_together(5)
    val lType = inL.type_decode_together(4)
    val iType = inL.type_decode_together(3)
    val sType = inL.type_decode_together(2)
    val rType = inL.type_decode_together(1)
    val other = inL.type_decode_together(0)
    val lui   = uType && inL.inst(5)
    val auipc = uType && !inL.inst(5)

    val actual_rdata1_pre = bypass(psrc1, inL.src1Data)
    val actual_rdata2_pre = bypass(psrc2, inL.src2Data)

    // ---- 【阶段 A1】lane>=1 源操作数再覆盖一层 lane0 同拍前递 ----
    // 若 lane0 本拍能产出结果，且其 pdst 命中当前 lane 的 psrc，则优先使用 lane0
    // 同拍结果；否则仍使用多级旁路/PRF 结果。
    val actual_rdata1 = if (k == 0) actual_rdata1_pre else {
      Mux(lane0CanForward && (lane0FwdPdst === psrc1) && (psrc1 =/= 0.U), lane0FwdData, actual_rdata1_pre)
    }
    val actual_rdata2 = if (k == 0) actual_rdata2_pre else {
      Mux(lane0CanForward && (lane0FwdPdst === psrc2) && (psrc2 =/= 0.U), lane0FwdData, actual_rdata2_pre)
    }

    val iChoose30OrNot = iType && (funct3 === "b101".U)
    val rChoose30OrNot = rType && ((funct3 === "b101".U) || (funct3 === "b000".U))
    val aluA = Mux1H(Seq(
      (bType || jalr || lType || sType || iType || rType) -> actual_rdata1,
      (auipc || jal) -> inL.pc,
      (lui || other) -> 0.U(32.W)
    ))
    val aluB = Mux1H(Seq(
      (bType || rType) -> actual_rdata2,
      (auipc || jal || jalr || lType || sType || iType) -> inL.imm,
      (lui || other) -> 0.U(32.W)
    ))
    val aluCtrl = Mux1H(Seq(
      bType -> Mux(funct3(2), Cat(0.U(2.W), funct3(2, 1)), 8.U(4.W)),
      iType -> Cat(iChoose30OrNot && inL.inst(30), funct3),
      rType -> Cat(rChoose30OrNot && inL.inst(30), funct3),
      !(bType || iType || rType) -> 0.U(4.W)
    ))
    val uALU = Module(new ALU)
    uALU.io.a      := aluA
    uALU.io.b      := aluB
    uALU.io.ctrl   := aluCtrl
    uALU.io.enable := !(lui || other)

    // ---- 分支验证（仅 lane0 会出现 bType/jalr）----
    val branch_taken = bType && (funct3(0) ^ (!uALU.io.zero) ^ funct3(2))
    val b_imm_ex = Cat(Fill(20, inL.inst(31)), inL.inst(7), inL.inst(30, 25), inL.inst(11, 8), 0.U(1.W))
    val b_target_ex = inL.pc + b_imm_ex
    val jalr_imm = Cat(Fill(20, inL.inst(31)), inL.inst(31, 20))
    val jalr_target_raw = actual_rdata1 + jalr_imm
    val jalr_target = Cat(jalr_target_raw(31, 1), 0.U(1.W))
    val b_mispredict = bType && (branch_taken ^ inL.predict_taken)
    // ---- 【阶段 δ】JALR 误预测判定：命中 BTB 且目标一致时不视为误预测 ----
    // Fetch 阶段 BTB 命中会把 predict_taken=true、predict_target=BTB.target；
    // 只要实际 jalr_target 与 predict_target 一致，就可免除流水线重定向。
    val jalr_mispredict = jalr && (!inL.predict_taken || inL.predict_target =/= jalr_target)
    val b_correct_addr = Mux(branch_taken, b_target_ex, inL.pc + 4.U(32.W))
    val actual_target_addr = Mux(jalr, jalr_target, b_correct_addr)
    val laneValid = in.valid && in.bits.validMask(k)
    val isMispredict = laneValid && (b_mispredict || jalr_mispredict)

    if (k == 0 && CPUConfig.useBHT) {
      bht_update.get.valid := laneValid && bType
      bht_update.get.idx   := inL.pc(bhtIdxWidth + 1, 2)
      bht_update.get.taken := branch_taken
    }

    // ---- 【阶段 δ】BTB 更新：每条解析的 JALR 都写回其实际目标 ----
    if (k == 0 && CPUConfig.useBTB) {
      btb_update.get.valid  := laneValid && jalr
      btb_update.get.pc     := inL.pc
      btb_update.get.target := jalr_target
    }

    outL.inst_funct3 := Mux(lType || sType, funct3, 0.U)
    outL.pdst := Mux(uType || jal || jalr || lType || iType || rType, pdst, 0.U)
    outL.data := Mux1H(Seq(
      lui -> inL.imm,
      (auipc || lType || sType || iType || rType) -> uALU.io.result,
      (jal || jalr) -> (inL.pc + 4.U(32.W)),
      (bType || other) -> 0.U(32.W)
    ))
    outL.reg_rdata2           := Mux(sType, actual_rdata2, 0.U)
    outL.type_decode_together := inL.type_decode_together
    outL.robIdx               := inL.robIdx
    outL.regWriteEnable       := inL.regWriteEnable
    outL.actual_target        := actual_target_addr
    outL.mispredict           := isMispredict
    outL.isSbAlloc            := inL.isSbAlloc
    outL.sbIdx                := inL.sbIdx
    outL.storeSeqSnap         := inL.storeSeqSnap
    outL.checkpointIdx        := inL.checkpointIdx

    // ---- 【阶段 A1】lane0 迭代结尾：把本拍结果导出为"同拍前递源" ----
    // 只有 lane0 需要导出；并且仅在 lane0 "能同拍产出结果" 时才允许前递。
    // 能同拍产出 <=> 非 Load（!lType），且有写回使能，pdst!=0，lane 本身有效。
    if (k == 0) {
      val canFwd = laneValid && inL.regWriteEnable && !lType && (outL.pdst =/= 0.U)
      lane0CanForward := canFwd
      lane0FwdPdst    := outL.pdst
      lane0FwdData    := outL.data
    }
  }

  out.bits.validMask := in.bits.validMask

  in.ready := out.ready
  out.valid := in.valid
}
