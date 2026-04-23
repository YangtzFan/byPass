package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig
import mycpu.AXISQQueryIO
import mycpu.AXISQLoadReqIO
import mycpu.AXISQLoadRespIO

// ============================================================================
// Memory（访存阶段）—— StoreBuffer / AXIStoreQueue / DRAM 三级访存前端
// ============================================================================
// 新版 load 处理顺序：
//   1. 先查 StoreBuffer（speculative store）；
//   2. 若无 olderUnknown，再按剩余 needMask 查 AXIStoreQueue（committed store）；
//   3. 若仍未 full cover，向 AXIStoreQueue 发起外部读请求；
//   4. 最终按优先级 StoreBuffer > AXIStoreQueue > DRAM 合并字节。
//
// 这样可以同时保证：
//   - 更老未知地址 store 仍会阻断 load；
//   - 已提交但未落地 DRAM 的 store 仍对后续 load 可见；
//   - 同一个 load 的不同字节可以分别来自 SB / SQ / DRAM。
// ============================================================================
class Memory extends Module {
  val in = IO(Flipped(Decoupled(new Execute_Memory_Payload)))
  val out = IO(Decoupled(new Memory_Refresh_Payload))

  // ---- StoreBuffer 接口 ----
  val sbQuery = IO(new SBQueryIO)
  val sbWrite = IO(Output(new SBWriteIO))

  // ---- AXIStoreQueue 前端接口 ----
  val sqQuery = IO(new AXISQQueryIO)
  val sqLoadReq = IO(new AXISQLoadReqIO)
  val sqLoadResp = IO(new AXISQLoadRespIO)

  // ---- Memory 阶段重定向输出 ----
  val redirect = IO(new Bundle {
    val valid = Output(Bool())
    val addr = Output(UInt(32.W))
    val robIdx = Output(UInt(CPUConfig.robPtrWidth.W))
    val storeSeqSnap = Output(UInt(CPUConfig.storeSeqWidth.W))
    val checkpointIdx = Output(UInt(CPUConfig.ckptPtrWidth.W))
  })

  // 指令类型解码
  val uType = in.bits.type_decode_together(8)
  val jal   = in.bits.type_decode_together(7)
  val jalr  = in.bits.type_decode_together(6)
  val bType = in.bits.type_decode_together(5)
  val lType = in.bits.type_decode_together(4)
  val iType = in.bits.type_decode_together(3)
  val sType = in.bits.type_decode_together(2)
  val rType = in.bits.type_decode_together(1)
  val addr  = in.bits.data

  val offset = addr(1, 0)
  val funct3 = in.bits.inst_funct3
  val func3_OH = UIntToOH(funct3(1, 0))

  // ---- Store 字节对齐 ----
  val storeRawData = in.bits.reg_rdata2
  val storeByteMask = Mux1H(Seq(
    func3_OH(0) -> (1.U(4.W) << offset),
    func3_OH(1) -> Mux(offset(1), "b1100".U(4.W), "b0011".U(4.W)),
    func3_OH(2) -> "b1111".U(4.W),
    func3_OH(3) -> 0.U(4.W)
  ))
  val storeByteData = Mux1H(Seq(
    func3_OH(0) -> (storeRawData(7, 0) << (offset << 3.U)),
    func3_OH(1) -> Mux(offset(1), Cat(storeRawData(15, 0), 0.U(16.W)), Cat(0.U(16.W), storeRawData(15, 0))),
    func3_OH(2) -> storeRawData,
    func3_OH(3) -> 0.U(32.W)
  ))

  // ---- Load 字节需求 ----
  val loadByteMask = Mux1H(Seq(
    func3_OH(0) -> (1.U(4.W) << offset),
    func3_OH(1) -> Mux(offset(1), "b1100".U(4.W), "b0011".U(4.W)),
    func3_OH(2) -> "b1111".U(4.W),
    func3_OH(3) -> 0.U(4.W)
  ))

  // ===================== StoreBuffer 写入 =====================
  sbWrite.valid := in.valid && sType && in.bits.isSbAlloc
  sbWrite.idx := in.bits.sbIdx
  sbWrite.addr := addr
  sbWrite.data := storeRawData
  sbWrite.mask := funct3
  sbWrite.byteMask := storeByteMask
  sbWrite.byteData := storeByteData

  // ===================== StoreBuffer 查询 =====================
  sbQuery.valid := in.valid && lType
  sbQuery.wordAddr := addr(31, 2)
  sbQuery.loadMask := loadByteMask
  sbQuery.storeSeqSnap := in.bits.storeSeqSnap

  // SB 只负责 speculative store，因此先把它已经覆盖的字节扣掉，再去查 committed queue。
  val sbFwdMask = sbQuery.fwdMask & loadByteMask
  val needAfterSB = loadByteMask & ~sbFwdMask

  // ===================== AXIStoreQueue 查询 / 外读接口默认值 =====================
  sqQuery.valid := false.B
  sqQuery.wordAddr := addr(31, 2)
  sqQuery.loadMask := 0.U

  sqLoadReq.valid := false.B
  sqLoadReq.addr := Cat(addr(31, 2), 0.U(2.W))
  sqLoadReq.needMask := 0.U
  sqLoadResp.ready := false.B

  // SQ forwarding 掩码只覆盖 SB 未覆盖的部分。
  val sqVisibleMask = WireInit(0.U(4.W))
  val sqVisibleData = WireInit(0.U(32.W))
  val needAfterSQ = WireInit(needAfterSB)

  when(in.valid && lType && !sbQuery.olderUnknown && (needAfterSB =/= 0.U)) {
    sqQuery.valid := true.B
    sqQuery.loadMask := needAfterSB
    sqVisibleMask := sqQuery.fwdMask & needAfterSB
    sqVisibleData := sqQuery.fwdData
    needAfterSQ := needAfterSB & ~sqVisibleMask
  }

  // ===================== 状态机 =====================
  val sIdle :: sWaitResp :: Nil = Enum(2)
  val state = RegInit(sIdle)

  // 进入等待外部读状态时，保存当拍的 SB/SQ forwarding 结果。
  val savedSbFwdMask = RegInit(0.U(4.W))
  val savedSbFwdData = RegInit(0.U(32.W))
  val savedSqFwdMask = RegInit(0.U(4.W))
  val savedSqFwdData = RegInit(0.U(32.W))

  val memStall = WireInit(false.B)
  val loadResult = WireInit(0.U(32.W))

  // ---- 辅助函数：按优先级合并 SB / SQ / DRAM 三路字节来源 ----
  private def mergeLoadSources(
    sbMask: UInt,
    sbData: UInt,
    sqMask: UInt,
    sqData: UInt,
    dramData: UInt
  ): UInt = {
    val mergedBytes = Wire(Vec(4, UInt(8.W)))
    for (b <- 0 until 4) {
      mergedBytes(b) := Mux(
        sbMask(b),
        sbData(b * 8 + 7, b * 8),
        Mux(
          sqMask(b),
          sqData(b * 8 + 7, b * 8),
          dramData(b * 8 + 7, b * 8)
        )
      )
    }
    Cat(mergedBytes(3), mergedBytes(2), mergedBytes(1), mergedBytes(0))
  }

  // ---- 辅助函数：按 funct3 / offset 对合并后的 32 位字进行提取和扩展 ----
  private def signExtendLoad(mergedWord: UInt, f3: UInt, off: UInt): UInt = {
    val result = Wire(UInt(32.W))
    val byte = (mergedWord >> (off << 3.U))(7, 0)
    val half = Mux(off(1), mergedWord(31, 16), mergedWord(15, 0))
    result := MuxLookup(f3, mergedWord)(Seq(
      "b000".U -> Cat(Fill(24, byte(7)), byte),
      "b001".U -> Cat(Fill(16, half(15)), half),
      "b010".U -> mergedWord,
      "b100".U -> Cat(0.U(24.W), byte),
      "b101".U -> Cat(0.U(16.W), half)
    ))
    result
  }

  switch(state) {
    is(sIdle) {
      when(in.valid && lType) {
        when(sbQuery.olderUnknown) {
          // 更老 speculative store 地址未知时，必须先停住，不能越过这道边界。
          memStall := true.B
        }.elsewhen(needAfterSQ === 0.U) {
          // SB + SQ 已经把本次 load 需要的字节全部覆盖，不需要访问 DRAM。
          val fullyMergedWord = mergeLoadSources(
            sbFwdMask,
            sbQuery.fwdData,
            sqVisibleMask,
            sqVisibleData,
            0.U(32.W)
          )
          loadResult := signExtendLoad(fullyMergedWord, funct3, offset)
          memStall := false.B
        }.otherwise {
          // 仍有未覆盖字节，需要通过 AXIStoreQueue 发起外部读。
          sqLoadReq.valid := true.B
          sqLoadReq.needMask := needAfterSQ
          when(sqLoadReq.valid && sqLoadReq.ready) {
            savedSbFwdMask := sbFwdMask
            savedSbFwdData := sbQuery.fwdData
            savedSqFwdMask := sqVisibleMask
            savedSqFwdData := sqVisibleData
            state := sWaitResp
          }
          memStall := true.B
        }
      }
    }

    is(sWaitResp) {
      sqLoadResp.ready := true.B
      memStall := true.B
      when(sqLoadResp.valid && sqLoadResp.ready) {
        val mergedWord = mergeLoadSources(
          savedSbFwdMask,
          savedSbFwdData,
          savedSqFwdMask,
          savedSqFwdData,
          sqLoadResp.rdata
        )
        loadResult := signExtendLoad(mergedWord, funct3, offset)
        memStall := false.B
        state := sIdle
      }
    }
  }

  // ===================== 重定向逻辑 =====================
  redirect.valid := in.valid && in.bits.mispredict && !memStall
  redirect.addr := in.bits.actual_target
  redirect.robIdx := in.bits.robIdx
  redirect.storeSeqSnap := in.bits.storeSeqSnap
  redirect.checkpointIdx := in.bits.checkpointIdx

  // ===================== 输出打包 =====================
  out.bits.pc := in.bits.pc
  out.bits.inst_rd := Mux(uType || jal || jalr || lType || iType || rType, in.bits.inst_rd, 0.U)
  out.bits.pdst := Mux(uType || jal || jalr || lType || iType || rType, in.bits.pdst, 0.U)
  out.bits.data := Mux1H(Seq(
    (uType || jal || jalr || iType || rType) -> in.bits.data,
    lType -> loadResult,
    (bType || sType) -> 0.U(32.W)
  ))
  out.bits.type_decode_together := in.bits.type_decode_together
  out.bits.robIdx := in.bits.robIdx
  out.bits.regWriteEnable := in.bits.regWriteEnable
  out.bits.isBranch := in.bits.isBranch
  out.bits.isJump := in.bits.isJump
  out.bits.predict_taken := in.bits.predict_taken
  out.bits.predict_target := in.bits.predict_target
  out.bits.actual_taken := in.bits.actual_taken
  out.bits.actual_target := in.bits.actual_target
  out.bits.mispredict := in.bits.mispredict
  out.bits.bht_meta := in.bits.bht_meta

  in.ready := out.ready && !memStall
  out.valid := in.valid && !memStall
}
