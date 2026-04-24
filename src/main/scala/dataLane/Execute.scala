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

    val actual_rdata1 = bypass(psrc1, inL.src1Data)
    val actual_rdata2 = bypass(psrc2, inL.src2Data)

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
    val b_correct_addr = Mux(branch_taken, b_target_ex, inL.pc + 4.U(32.W))
    val actual_target_addr = Mux(jalr, jalr_target, b_correct_addr)
    val laneValid = in.valid && in.bits.validMask(k)
    val isMispredict = laneValid && (b_mispredict || jalr)

    if (k == 0 && CPUConfig.useBHT) {
      bht_update.get.valid := laneValid && bType
      bht_update.get.idx   := inL.pc(bhtIdxWidth + 1, 2)
      bht_update.get.taken := branch_taken
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
  }

  out.bits.validMask := in.bits.validMask

  in.ready := out.ready
  out.valid := in.valid
}
