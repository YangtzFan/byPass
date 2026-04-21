package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// Memory（访存阶段）—— LSU 前端 + 分支纠错重定向 + StoreBuffer 字节级转发
// ============================================================================
// 两级状态机设计，支持多周期 Load：
//   sIdle:
//     - Store 指令：计算 byteMask/byteData，写入 StoreBuffer，直接透传
//     - Load  指令：查询 SB 字节级转发，4 种情况：
//         A) olderUnknown → 停顿等待
//         B) fullCover   → 纯 SB 转发，立即输出（不读外部）
//         C) 需要外部读  → 发出 lsuLoadReq，保存 fwdMask/fwdData → sWaitResp
//         D) lsuLoadReq 未就绪 → 停顿等待仲裁器
//     - 其他指令：直接透传，处理分支重定向
//   sWaitResp:
//     - 等待 lsuLoadResp 返回
//     - 将保存的 SB 转发数据与外部读回数据按字节合并
//     - 符号扩展后输出
//
// 关键变更（相对旧版）：
//   1. 移除直接 DRAM 端口，改用 DecoupledIO lsuLoadReq/lsuLoadResp
//   2. 移除 drainActive 信号（由 LSU Arbiter 统一仲裁）
//   3. 符号扩展从 dram_driver 移到本阶段
//   4. StoreBuffer 查询改为字节级（wordAddr + loadMask）
// ============================================================================
class Memory extends Module {
  val in  = IO(Flipped(Decoupled(new Execute_Memory_Payload)))
  val out = IO(Decoupled(new Memory_Refresh_Payload))

  // ---- LSU Load 请求/响应接口（连接到 LSU Arbiter）----
  val lsuLoadReq  = IO(Decoupled(new MemReqBundle))
  val lsuLoadResp = IO(Flipped(Decoupled(new MemRespBundle)))

  // ---- StoreBuffer 写入端口（Store 指令写入地址、数据、字节级掩码）----
  val sbWrite = IO(Output(new SBWriteIO))

  // ---- StoreBuffer 查询接口（Load 指令字节级转发查询）----
  val sbQuery = IO(new SBQueryIO)

  // ---- Memory 阶段重定向输出 ----
  val redirect = IO(new Bundle {
    val valid         = Output(Bool())
    val addr          = Output(UInt(32.W))
    val robIdx        = Output(UInt(CPUConfig.robPtrWidth.W))
    val storeSeqSnap  = Output(UInt(CPUConfig.storeSeqWidth.W))
    val checkpointIdx = Output(UInt(CPUConfig.ckptPtrWidth.W))
  })

  // 指令类型解码
  val uType = in.bits.type_decode_together(8)
  val jal   = in.bits.type_decode_together(7)
  val jalr  = in.bits.type_decode_together(6)
  val bType = in.bits.type_decode_together(5)
  val lType = in.bits.type_decode_together(4) // Load
  val iType = in.bits.type_decode_together(3)
  val sType = in.bits.type_decode_together(2) // Store
  val rType = in.bits.type_decode_together(1)
  val other = in.bits.type_decode_together(0)

  // 字节对齐计算（Store 和 Load 共用）
  val addr    = in.bits.data          // ALU 计算出的访存地址
  val offset  = addr(1, 0)            // 字内字节偏移
  val funct3  = in.bits.inst_funct3   // 访存宽度标识

  // ---- Store 字节掩码和字节对齐数据 ----
  // SB: funct3=000 → 1 字节
  // SH: funct3=001 → 2 字节（半字对齐）
  // SW: funct3=010 → 4 字节
  val storeRawData = in.bits.reg_rdata2  // rs2 的原始值
  val storeByteMask = MuxLookup(funct3(1, 0), 0.U)(Seq(
    0.U -> (1.U(4.W) << offset),                           // SB：单字节
    1.U -> Mux(offset(1), "b1100".U(4.W), "b0011".U(4.W)), // SH：按半字对齐
    2.U -> "b1111".U(4.W)                                  // SW：全字
  ))
  val storeByteData = MuxLookup(funct3(1, 0), 0.U)(Seq(
    0.U -> (storeRawData(7, 0) << (offset << 3.U)),        // SB：字节移位到目标位置
    1.U -> Mux(offset(1),
      Cat(storeRawData(15, 0), 0.U(16.W)),                 // SH 高半字
      Cat(0.U(16.W), storeRawData(15, 0))),                // SH 低半字
    2.U -> storeRawData                                    // SW：全字直接写
  ))

  // ---- Load 字节掩码 ----
  // LB/LBU: funct3[1:0]=00 → 1 字节
  // LH/LHU: funct3[1:0]=01 → 2 字节
  // LW:     funct3[1:0]=10 → 4 字节
  val loadByteMask = MuxLookup(funct3(1, 0), 0.U)(Seq(
    0.U -> (1.U(4.W) << offset),                           // LB/LBU
    1.U -> Mux(offset(1), "b1100".U(4.W), "b0011".U(4.W)), // LH/LHU
    2.U -> "b1111".U(4.W)                                  // LW
  ))

  // ========================================================================
  // StoreBuffer 写入（Store 指令 — 在 sIdle 阶段完成）
  // ========================================================================
  sbWrite.valid    := in.valid && sType && in.bits.isSbAlloc
  sbWrite.idx      := in.bits.sbIdx
  sbWrite.addr     := addr          // 原始完整字节地址（difftest 用）
  sbWrite.data     := storeRawData  // 原始 rs2 数据（difftest 用）
  sbWrite.mask     := funct3        // 原始 funct3（difftest 用）
  sbWrite.byteMask := storeByteMask // 字节写使能掩码
  sbWrite.byteData := storeByteData // 字节对齐后的写数据

  // ========================================================================
  // StoreBuffer 查询（Load 指令 — 字节级转发）
  // ========================================================================
  sbQuery.valid        := in.valid && lType
  sbQuery.wordAddr     := addr(31, 2)          // 字对齐地址
  sbQuery.loadMask     := loadByteMask         // Load 需要的字节掩码
  sbQuery.storeSeqSnap := in.bits.storeSeqSnap

  // ========================================================================
  // 状态机
  // ========================================================================
  val sIdle :: sWaitResp :: Nil = Enum(2)
  val state = RegInit(sIdle)

  // 保存从 sIdle 转入 sWaitResp 时的 SB 转发快照
  val savedFwdMask = RegInit(0.U(4.W))
  val savedFwdData = RegInit(0.U(32.W))

  // 默认输出
  lsuLoadReq.valid  := false.B
  lsuLoadReq.bits   := 0.U.asTypeOf(new MemReqBundle)
  lsuLoadResp.ready := false.B

  // ---- 停顿/输出控制信号 ----
  val memStall   = WireInit(false.B)   // 综合停顿信号
  val loadResult = WireInit(0.U(32.W)) // Load 最终结果（符号扩展后）

  // ---- 符号扩展辅助函数 ----
  // 从合并后的 32 位字中按 funct3 和 offset 提取并扩展
  private def signExtendLoad(mergedWord: UInt, f3: UInt, off: UInt): UInt = {
    val result = Wire(UInt(32.W))
    val byte = (mergedWord >> (off << 3.U))(7, 0)
    val half = Mux(off(1),
      mergedWord(31, 16),
      mergedWord(15, 0))
    result := MuxLookup(f3, mergedWord)(Seq(
      "b000".U -> Cat(Fill(24, byte(7)), byte),  // LB：符号扩展
      "b001".U -> Cat(Fill(16, half(15)), half), // LH：符号扩展
      "b010".U -> mergedWord,                    // LW：全字
      "b100".U -> Cat(0.U(24.W), byte),          // LBU：零扩展
      "b101".U -> Cat(0.U(16.W), half)           // LHU：零扩展
    ))
    result
  }

  switch(state) {
    is(sIdle) {
      when(in.valid && lType) {
        // ---- Load 指令处理 ----
        when(sbQuery.olderUnknown) {
          // 情况 A：有更老 Store 地址未知 → 停顿
          memStall := true.B
        }.elsewhen(sbQuery.fullCover) {
          // 情况 B：SB 完全覆盖 → 纯转发，不需要外部读
          loadResult := signExtendLoad(sbQuery.fwdData, funct3, offset)
          memStall := false.B
        }.otherwise {
          // 情况 C/D：需要外部读 → 发出 LSU 请求
          lsuLoadReq.valid       := true.B
          lsuLoadReq.bits.isWrite := false.B
          lsuLoadReq.bits.addr   := Cat(addr(31, 2), 0.U(2.W)) // 字对齐地址
          lsuLoadReq.bits.wdata  := 0.U
          lsuLoadReq.bits.wstrb  := 0.U
          when(lsuLoadReq.fire) {
            // 请求发出成功 → 保存 SB 转发快照，进入等待状态
            savedFwdMask := sbQuery.fwdMask
            savedFwdData := sbQuery.fwdData
            state := sWaitResp
          }
          memStall := true.B  // 请求未完成期间停顿
        }
      }
      // Store / 其他指令在 sIdle 直接透传，不停顿
    }

    is(sWaitResp) {
      // 等待外部 Load 响应
      lsuLoadResp.ready := true.B
      memStall := true.B  // 等待期间停顿

      when(lsuLoadResp.fire) {
        // 响应到达 → 合并 SB 转发和外部读回的数据
        val externalRdata = lsuLoadResp.bits.rdata
        // 按字节合并：fwdMask 对应位用 SB 数据，否则用外部数据
        val mergedWord = Wire(UInt(32.W))
        val mergedBytes = Wire(Vec(4, UInt(8.W)))
        for (b <- 0 until 4) {
          mergedBytes(b) := Mux(savedFwdMask(b),
            savedFwdData(b * 8 + 7, b * 8),      // SB 转发字节
            externalRdata(b * 8 + 7, b * 8))      // 外部读回字节
        }
        mergedWord := Cat(mergedBytes(3), mergedBytes(2), mergedBytes(1), mergedBytes(0))

        // 符号扩展
        loadResult := signExtendLoad(mergedWord, funct3, offset)
        memStall := false.B
        state := sIdle
      }
    }
  }

  // ========================================================================
  // 重定向逻辑（仅 sIdle 且非停顿时触发）
  // ========================================================================
  redirect.valid         := in.valid && in.bits.mispredict && !memStall
  redirect.addr          := in.bits.actual_target
  redirect.robIdx        := in.bits.robIdx
  redirect.storeSeqSnap  := in.bits.storeSeqSnap
  redirect.checkpointIdx := in.bits.checkpointIdx

  // ========================================================================
  // 输出打包
  // ========================================================================
  out.bits.pc := in.bits.pc
  out.bits.inst_rd := Mux(uType || jal || jalr || lType || iType || rType, in.bits.inst_rd, 0.U)
  out.bits.pdst := Mux(uType || jal || jalr || lType || iType || rType, in.bits.pdst, 0.U)
  out.bits.data := Mux1H(Seq(
    (uType || jal || jalr || iType || rType) -> in.bits.data,
    lType -> loadResult,
    (bType || sType) -> 0.U(32.W)
  ))
  out.bits.type_decode_together := in.bits.type_decode_together
  out.bits.robIdx         := in.bits.robIdx
  out.bits.regWriteEnable := in.bits.regWriteEnable
  out.bits.isBranch       := in.bits.isBranch
  out.bits.isJump         := in.bits.isJump
  out.bits.predict_taken  := in.bits.predict_taken
  out.bits.predict_target := in.bits.predict_target
  out.bits.actual_taken   := in.bits.actual_taken
  out.bits.actual_target  := in.bits.actual_target
  out.bits.mispredict     := in.bits.mispredict
  out.bits.bht_meta       := in.bits.bht_meta

  // ========================================================================
  // 握手信号
  // ========================================================================
  in.ready  := out.ready && !memStall
  out.valid := in.valid && !memStall
}
