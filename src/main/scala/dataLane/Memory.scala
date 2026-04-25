package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig
import mycpu.memory.AXISQQueryIO

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
//
// 阶段 η2（MSHR 非阻塞 Load）：
//   - 引入单项 MSHR（Miss Status Holding Register）：当 Load 必须发起 DRAM 外读时，
//     不再用 sWaitResp 把整条流水线冻结，而是把 Load 的 pdst/robIdx/funct3/offset/
//     已转发字节等保存到 MSHR；本拍 Memory.in 立即 fire（in.ready=true），后续
//     Store/ALU/Branch 可继续推进；
//   - 该 missed Load 的 lane0 在 Memory.out 中被设为 invalid（validMask(0)=0），
//     从而 Refresh 不会写 PRF / 不会标记 ROB done / 不会唤醒消费者；
//   - 当 sqLoadData 返回时，MSHR 合并最终结果；MyCPU 顶层用一个空闲的 Refresh 写
//     端口（写口仲裁）写 PRF + 标记 ROB.done + 置 ReadyTable + 广播 wakeup；
//   - 消费者由 Issue.scala 的第 4 个 hazard 源（MSHR-pending）阻塞，直到 MSHR
//     完成那拍 ack 触发后才放行；保证 RAW 安全；
//   - 分支误预测时若 MSHR 中的 Load 比误预测分支更年轻，标记 mshrFlushed，DRAM
//     响应回来后丢弃数据，不写 PRF。
// ============================================================================
class Memory extends Module {
  val in = IO(Flipped(Decoupled(new Execute_Memory_Payload)))
  val out = IO(Decoupled(new Memory_Refresh_Payload))

  // ---- StoreBuffer 接口 ----
  val sbQuery = IO(new SBQueryIO)
  val sbWrite = IO(Output(new SBWriteIO))

  // ---- AXIStoreQueue 前端接口 ----
  // committed-store 查询为纯组合接口；外部读请求/响应统一改为 Decoupled。
  val sqQuery    = IO(new AXISQQueryIO)
  val sqLoadAddr  = IO(Decoupled(UInt(32.W)))
  val sqLoadData = IO(Flipped(Decoupled(UInt(32.W))))

  // ---- Memory 阶段重定向输出 ----
  val redirect = IO(new Bundle {
    val valid = Output(Bool())
    val addr = Output(UInt(32.W))
    val robIdx = Output(UInt(CPUConfig.robPtrWidth.W))
    val storeSeqSnap = Output(UInt(CPUConfig.storeSeqWidth.W))
    val checkpointIdx = Output(UInt(CPUConfig.ckptPtrWidth.W))
  })

  // ---- η2：MSHR 完成接口（向 MyCPU 顶层发起 PRF/ROB/ReadyTable/wakeup 写口仲裁）----
  // 当 MSHR 中的 Load 已收到 DRAM 响应（或本拍即将收到），mshrComplete.valid=1；
  // MyCPU 顶层用一个空闲的 Refresh 写端口接管它；ack=1 表示本拍写口已仲裁成功，
  // MSHR 在本拍末尾清空 valid。
  val mshrComplete = IO(new Bundle {
    val valid          = Output(Bool())
    val pdst           = Output(UInt(CPUConfig.prfAddrWidth.W))
    val data           = Output(UInt(32.W))
    val robIdx         = Output(UInt(CPUConfig.robPtrWidth.W))
    val regWriteEnable = Output(Bool())
    val ack            = Input(Bool())
  })

  // ---- η2：MSHR 当前持有的 Load 信息（向 Issue 阶段提供 hazard #3 的 pdst）----
  val mshrPending = IO(Output(new Bundle {
    val valid          = Bool()
    val pdst           = UInt(CPUConfig.prfAddrWidth.W)
    val regWriteEnable = Bool()
  }))

  // ---- η2：分支误预测 flush 输入（用于 c 步：清掉年轻于分支的 MSHR 条目）----
  val flushIn = IO(Input(new Bundle {
    val valid        = Bool()
    val branchRobIdx = UInt(CPUConfig.robPtrWidth.W)
  }))

  // ---- η2：本拍是否新捕获 Load 进入 MSHR（供 MyCPU γ-wake 抑制使用，b 步）----
  val mshrCaptureFire = IO(Output(Bool()))

  // ---- lane0（全功能访存）----
  val inL  = in.bits.lanes(0)
  val outL = out.bits.lanes(0)
  out.bits := DontCare

  // 指令类型解码
  val uType = inL.type_decode_together(8)
  val jal   = inL.type_decode_together(7)
  val jalr  = inL.type_decode_together(6)
  val bType = inL.type_decode_together(5)
  val lType = inL.type_decode_together(4)
  val iType = inL.type_decode_together(3)
  val sType = inL.type_decode_together(2)
  val rType = inL.type_decode_together(1)
  val addr  = inL.data

  val offset = addr(1, 0)
  val funct3 = inL.inst_funct3
  val func3_OH = UIntToOH(funct3(1, 0))

  // ---- Store 字节对齐 ----
  val storeRawData = inL.reg_rdata2
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
  // 必须门控 validMask(0)，否则 lane0 被选择性 flush 后 sType/isSbAlloc 字段仍可能匹配导致误写
  val lane0Valid = in.valid && in.bits.validMask(0)
  sbWrite.valid := lane0Valid && sType && inL.isSbAlloc
  sbWrite.idx := inL.sbIdx
  sbWrite.addr := addr
  sbWrite.data := storeRawData
  sbWrite.mask := funct3
  sbWrite.byteMask := storeByteMask
  sbWrite.byteData := storeByteData

  // ===================== StoreBuffer 查询 =====================
  sbQuery.valid := lane0Valid && lType
  sbQuery.wordAddr := addr(31, 2)
  sbQuery.loadMask := loadByteMask
  sbQuery.storeSeqSnap := inL.storeSeqSnap

  // SB 只负责 speculative store，因此先把它已经覆盖的字节扣掉，再去查 committed queue。
  val sbFwdMask = sbQuery.fwdMask & loadByteMask

  // ===================== AXIStoreQueue 查询（阶段 η1：与 SB 并行发起） =====================
  // 变更点：SQ 查询不再等待 SB 的 needAfterSB 结果，而是直接以完整 loadByteMask 发起。
  //   1. SB/SQ 是两张独立的表，各自组合查询彼此无依赖；
  //   2. 合并阶段按 SB 优先级覆盖：sqVisibleMask = sqRawMask & ~sbFwdMask；
  //   3. needAfterSQ 由两者并集补全 loadByteMask 得到，用于判断是否还需要 DRAM 外读；
  //   4. 这样把原来的 SB→needAfterSB→SQ 串行组合链拆为"SB/SQ 同拍查询 + 事后合并"并行结构，
  //      关键路径变短，并消除了 needAfterSB 这一层中间线。
  sqQuery.valid := lane0Valid && lType && !sbQuery.olderUnknown
  sqQuery.wordAddr := addr(31, 2)
  sqQuery.loadMask := loadByteMask

  // SQ 原始可见字节 → 按 SB 优先级裁剪 → 得到 SQ 对 Load 的实际贡献字节。
  val sqRawMask     = sqQuery.fwdMask & loadByteMask
  val sqVisibleMask = sqRawMask & ~sbFwdMask
  val sqVisibleData = sqQuery.fwdData

  // 仅 Load 有效时参与合并；Store/其它指令 needAfterSQ 无意义，保持 0 即可。
  val needAfterSQ = Mux(lane0Valid && lType,
    loadByteMask & ~(sbFwdMask | sqVisibleMask),
    0.U(4.W))

  // ===================== η2：MSHR（单项）=====================
  // mshrValid          : 当前是否有 outstanding load（已发出 sqLoadAddr 等待响应/写回）
  // mshrResultValid    : sqLoadData 已返回、loadResult 已合并好，但还未拿到写口 ack
  // mshrFlushed        : 本 MSHR 已被分支误预测清掉，等 sqLoadData 返回后丢弃
  val mshrValid          = RegInit(false.B)
  val mshrResultValid    = RegInit(false.B)
  val mshrFlushed        = RegInit(false.B)
  val mshrPdst           = Reg(UInt(CPUConfig.prfAddrWidth.W))
  val mshrRobIdx         = Reg(UInt(CPUConfig.robPtrWidth.W))
  val mshrRegWriteEnable = Reg(Bool())
  val mshrFunct3         = Reg(UInt(3.W))
  val mshrOffset         = Reg(UInt(2.W))
  val mshrSavedSbMask    = Reg(UInt(4.W))
  val mshrSavedSbData    = Reg(UInt(32.W))
  val mshrSavedSqMask    = Reg(UInt(4.W))
  val mshrSavedSqData    = Reg(UInt(32.W))
  val mshrResultData     = Reg(UInt(32.W))

  // ===================== Load 完成判定 =====================
  // 三条互斥分支：
  //   1) olderUnknown：老 store 地址未知 → 必须等待，memStall=true
  //   2) needAfterSQ === 0：SB+SQ 全覆盖 → 本拍即可完成
  //   3) needAfterSQ =/= 0：必须发起 DRAM 外读 → 走 MSHR 路径
  val memStall   = WireInit(false.B)
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

  // 全覆盖（无需 DRAM）的合并结果
  val fullyMergedWord = mergeLoadSources(
    sbFwdMask,
    sbQuery.fwdData,
    sqVisibleMask,
    sqVisibleData,
    0.U(32.W)
  )
  val fullCoverResult = signExtendLoad(fullyMergedWord, funct3, offset)

  // 当前周期是否要新捕获 Load 进入 MSHR
  val needDram   = lane0Valid && lType && !sbQuery.olderUnknown && (needAfterSQ =/= 0.U)
  // 若 MSHR 这拍正完成（ack=1），本拍可直接复用：mshrFreeNow=true
  val mshrFreeNow = !mshrValid || mshrComplete.ack
  val newLoadCanCapture = needDram && mshrFreeNow

  // sqLoadAddr 的 valid/addr：仅在 newLoadCanCapture 时抬起
  sqLoadAddr.valid := newLoadCanCapture
  sqLoadAddr.bits  := Cat(addr(31, 2), 0.U(2.W))

  // sqLoadData.ready：MSHR 在飞且本拍要么尚未把结果寄存，要么已被 flush 需要排掉残留。
  // 不引入 mshrComplete.ack 同拍路径以避免 firtool 的 combinational cycle。
  sqLoadData.ready := mshrValid && (!mshrResultValid || mshrFlushed)

  // 本拍合并出来的"新鲜结果"（仅 sqLoadData.fire 时有意义）
  val freshlyMergedWord = mergeLoadSources(
    mshrSavedSbMask, mshrSavedSbData,
    mshrSavedSqMask, mshrSavedSqData,
    sqLoadData.bits
  )
  val freshlyComputedResult = signExtendLoad(freshlyMergedWord, mshrFunct3, mshrOffset)

  // mshrComplete 输出（向顶层请求写口）
  // 仅在 mshrResultValid=1（结果已寄存）时申请写口，避免 sqLoadData.fire→ack 同拍组合环。
  mshrComplete.valid          := mshrValid && !mshrFlushed && mshrResultValid
  mshrComplete.pdst           := mshrPdst
  mshrComplete.data           := mshrResultData
  mshrComplete.robIdx         := mshrRobIdx
  mshrComplete.regWriteEnable := mshrRegWriteEnable

  // 暴露给 Issue 的 hazard #3：MSHR 持有指令的 pdst；本拍若 ack 则等价于已写完，hazard 同拍清除
  mshrPending.valid          := mshrValid && !mshrComplete.ack
  mshrPending.pdst           := mshrPdst
  mshrPending.regWriteEnable := mshrRegWriteEnable

  // 决定本拍是否实际把 Load 捕入 MSHR
  val mshrCaptureFireWire = newLoadCanCapture && sqLoadAddr.fire
  mshrCaptureFire := mshrCaptureFireWire

  // ===================== memStall 决策 =====================
  // Load 路径外的指令一律可推进；只有 lType 才可能引入停顿。
  when(lane0Valid && lType) {
    when(sbQuery.olderUnknown) {
      memStall := true.B
    }.elsewhen(needAfterSQ === 0.U) {
      loadResult := fullCoverResult
      memStall := false.B
    }.otherwise {
      // 需要 DRAM
      when(!mshrFreeNow) {
        memStall := true.B
      }.otherwise {
        // sqLoadAddr.fire 才算真正发出请求；否则下游 SQ/AXI 暂时拒绝，需要重试
        memStall := !sqLoadAddr.fire
      }
    }
  }

  // ===================== MSHR 状态更新 =====================
  // ack 优先级最高：本拍写口仲裁成功即清空 MSHR 的所有标志。
  // 否则若 sqLoadData.fire（有响应回来）：
  //   - 已被 flush：直接清；
  //   - 未被 flush：把结果暂存到 mshrResultData，置 mshrResultValid 等下拍写口。
  // 否则若新的 Load 通过 sqLoadAddr.fire 进入 MSHR：捕获其字段。
  // flushIn 检测：本拍若分支误预测 redirect 且 MSHR 中的 Load robIdx 比误预测分支更年轻，
  //   置 mshrFlushed=1；DRAM 响应回来后丢弃。
  private def isYoungerRob(a: UInt, b: UInt): Bool = {
    val w = a.getWidth
    ((a - b)(w - 1) === 0.U) && (a =/= b)
  }
  val mshrShouldFlush = flushIn.valid && mshrValid && isYoungerRob(mshrRobIdx, flushIn.branchRobIdx)

  when(mshrComplete.ack) {
    mshrValid       := false.B
    mshrResultValid := false.B
    mshrFlushed    := false.B
    // ack 与同拍捕获新 Load 互斥：本拍要么写口完成、要么发起新 Load；
    // sqLoadAddr.valid 仅在 mshrFreeNow=true 时抬起，本拍 mshrFreeNow=true（因 ack=1），
    // 因此可能同拍既 ack 旧的、又捕获新的（管线最高吞吐场景）。
    when(mshrCaptureFireWire) {
      mshrValid          := true.B
      mshrPdst           := inL.pdst
      mshrRobIdx         := inL.robIdx
      mshrRegWriteEnable := inL.regWriteEnable
      mshrFunct3         := funct3
      mshrOffset         := offset
      mshrSavedSbMask    := sbFwdMask
      mshrSavedSbData    := sbQuery.fwdData
      mshrSavedSqMask    := sqVisibleMask
      mshrSavedSqData    := sqVisibleData
    }
  }.elsewhen(mshrFlushed && sqLoadData.fire) {
    // flush 后等响应回来再清，避免 axi.r 残留污染下次使用
    mshrValid       := false.B
    mshrResultValid := false.B
    mshrFlushed    := false.B
  }.elsewhen(mshrValid && sqLoadData.fire && !mshrFlushed) {
    // 响应回来：要么暂存结果，要么同拍被 flush 直接清掉。
    when(mshrShouldFlush) {
      // 同拍既来响应又被 flush：data 已被 AXI 吐出，MSHR 直接整体清掉。
      // 若不在这里清，则 mshrFlushed=1 以后没有新的 sqLoadData.fire 可以触发清理路径，
      // 会导致 MSHR 永久占用 → 上游 Issue 的 hazard #3 永久阻塞 → 整个流水线死锁。
      mshrValid       := false.B
      mshrResultValid := false.B
      mshrFlushed     := false.B
    }.otherwise {
      mshrResultValid := true.B
      mshrResultData  := freshlyComputedResult
    }
  }.elsewhen(mshrCaptureFireWire) {
    // 本拍捕获新 Load
    mshrValid          := true.B
    mshrPdst           := inL.pdst
    mshrRobIdx         := inL.robIdx
    mshrRegWriteEnable := inL.regWriteEnable
    mshrFunct3         := funct3
    mshrOffset         := offset
    mshrSavedSbMask    := sbFwdMask
    mshrSavedSbData    := sbQuery.fwdData
    mshrSavedSqMask    := sqVisibleMask
    mshrSavedSqData    := sqVisibleData
    mshrResultValid    := false.B
    mshrFlushed        := false.B
  }.elsewhen(mshrShouldFlush) {
    // 单纯被 flush（响应未回 / 或响应已回但未拿到写口）：
    //   - mshrResultValid=0：数据还没回，标 mshrFlushed=1，等响应到来后再清；
    //   - mshrResultValid=1：数据已经在 MSHR 中（只是写口还没仲裁到），直接整体清掉；
    //     如果只 set mshrFlushed=1 而不清 mshrValid，AXI 已不会再来响应，唯一清路径
    //     mshrFlushed && sqLoadData.fire 永远不触发 → 死锁。
    when(mshrResultValid) {
      mshrValid       := false.B
      mshrResultValid := false.B
      mshrFlushed     := false.B
    }.otherwise {
      mshrFlushed := true.B
    }
  }

  // ===================== 握手信号 =====================
  // memStall 由 Load 的多拍访存（sWaitResp / olderUnknown / sqLoadAddr 未 fire）驱动；
  // 本阶段无法前进时，既不向上游 ready，也不向下游 valid。
  in.ready  := !memStall
  out.valid := in.valid && !memStall

  // ===================== 重定向逻辑 =====================
  redirect.valid := in.valid && in.bits.validMask(0) && inL.mispredict && !memStall
  redirect.addr := inL.actual_target
  redirect.robIdx := inL.robIdx
  redirect.storeSeqSnap := inL.storeSeqSnap
  redirect.checkpointIdx := inL.checkpointIdx
  // 提前声明 lane0MispredictKill（后续 lane1+ passthrough 会读取）
  val lane0MispredictKill = WireInit(false.B)
  lane0MispredictKill := redirect.valid

  // ===================== 输出打包 =====================
  outL.pdst := Mux(uType || jal || jalr || lType || iType || rType, inL.pdst, 0.U)
  outL.data := Mux1H(Seq(
    (uType || jal || jalr || iType || rType) -> inL.data,
    lType -> loadResult,
    (bType || sType) -> 0.U(32.W)
  ))
  outL.robIdx := inL.robIdx
  outL.regWriteEnable := inL.regWriteEnable

  // ===================== lane1..W-1 passthrough（纯 ALU，无访存）=====================
  // lane1 不会是 Load/Store/Branch/JALR，直接把 Execute 的结果搬运到 Refresh。
  // 如果 lane0 本拍 mispredict，lane1 是同对内的年轻指令，必须被 kill（validMask=0），
  // 否则会污染 Refresh → ROB/PRF（Memory 已触发 redirect，上游会 flush，但本对的 lane1
  // 仍在管线中，不 kill 将错误 refresh）。
  for (k <- 1 until CPUConfig.memoryWidth) {
    val inLk  = in.bits.lanes(k)
    val outLk = out.bits.lanes(k)
    outLk.pdst            := inLk.pdst
    outLk.data            := inLk.data
    outLk.robIdx          := inLk.robIdx
    outLk.regWriteEnable  := inLk.regWriteEnable
  }

  // 组装 out.bits.validMask：
  //   - lane0：mshrCaptureFireWire=1 时（Load 进 MSHR）必须置 0，让 Refresh 跳过本拍，
  //     等 MSHR 完成时由 MyCPU 顶层用空闲 Refresh 写口接管 PRF/ROB.refresh/wakeup；
  //   - lane1+：被 lane0 mispredict 清零（保留原有 selective flush）。
  val outMaskBits = Wire(Vec(CPUConfig.memoryWidth, Bool()))
  outMaskBits(0) := in.bits.validMask(0) && !mshrCaptureFireWire
  for (k <- 1 until CPUConfig.memoryWidth) {
    outMaskBits(k) := in.bits.validMask(k) && !lane0MispredictKill
  }
  out.bits.validMask := outMaskBits.asUInt
}
