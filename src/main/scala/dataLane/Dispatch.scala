package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// Dispatch（派发阶段）—— 宽度 1
// ============================================================================
// 接收 1 条译码后的指令，分配 ROB 表项，然后传给 Execute 阶段
// 只有当 ROB 还有空余表项时才能派发，否则反压前级
// ============================================================================
class Dispatch extends Module {
  val in = IO(Flipped(Decoupled(new Decode_Dispatch_Payload)))   // 输入：译码结果
  val out = IO(Decoupled(new Dispatch_Execute_Payload))          // 输出：派发结果（含 ROB 索引）

  // Commit 阶段 flush 信号（预测错误时清空流水线）flush 时不向下游输出有效指令，也不分配 ROB 表项
  val flush = IO(Input(Bool()))

  // ---- ROB 分配接口（使用打包 Bundle）----
  // 包含分配请求数据（Dispatch → ROB）和反向应答（ROB → Dispatch）
  val robAlloc = IO(new ROBAllocIO)

  // ---- 寄存器堆读取接口 ----
  // 寄存器读取从 Decode 移至 Dispatch，更接近 Execute 阶段，仅随 DispExDff 1 拍即到达 Execute
  val regRead = IO(new Bundle {
    val raddr1 = Output(UInt(5.W)) // 读 rs1 地址
    val raddr2 = Output(UInt(5.W)) // 读 rs2 地址
    val rdata1 = Input(UInt(32.W)) // rs1 数据
    val rdata2 = Input(UInt(32.W)) // rs2 数据
  })

  // 从指令中提取寄存器编号
  val opcode = in.bits.inst(6, 0)
  val rd = in.bits.inst(11, 7)
  val rs1 = in.bits.inst(19, 15)
  val rs2 = in.bits.inst(24, 20)

  // 从类型编码中提取各指令类型
  val uType = in.bits.type_decode_together(8)
  val jal = in.bits.type_decode_together(7)
  val jalr = in.bits.type_decode_together(6)
  val bType = in.bits.type_decode_together(5)   // 分支指令
  val lType = in.bits.type_decode_together(4)
  val iType = in.bits.type_decode_together(3)
  val sType = in.bits.type_decode_together(2)   // Store 指令
  val rType = in.bits.type_decode_together(1)

  // 判断是否需要写回寄存器（U/JAL/JALR/Load/I/R 型都会写 rd）
  val regWriteEnable = uType || jal || jalr || lType || iType || rType
  // 只有 ROB 有空闲表项时才能派发
  val canDispatch = robAlloc.ready
  // flush 时不分配，防止错误路径指令浮用 ROB 资源
  robAlloc.valid := in.valid && out.ready && canDispatch && !flush

  // ---- ROB 分配请求数据输出 ----
  // 将当前指令的信息打包输出到 ROBAllocIO，由 <> 连接到 ROB
  robAlloc.pc            := in.bits.pc
  robAlloc.inst          := in.bits.inst
  robAlloc.rd            := rd
  robAlloc.regWen        := regWriteEnable
  robAlloc.isLoad        := lType         // Load 指令
  robAlloc.isStore       := sType         // Store 指令
  robAlloc.isBranch      := bType         // 分支指令
  robAlloc.isJump        := jal || jalr   // JAL JALR
  robAlloc.predictTaken  := in.bits.predict_taken
  robAlloc.predictTarget := in.bits.predict_target
  robAlloc.bhtMeta       := in.bits.bht_meta

  // ---- 寄存器堆读取 ----
  // 根据指令类型判断是否需要读取 rs1/rs2，不使用时读 x0（恒为 0）
  val use_rs1 = jalr || bType || iType || sType || rType || lType
  val use_rs2 = bType || sType || rType
  regRead.raddr1 := Mux(use_rs1, rs1, 0.U)
  regRead.raddr2 := Mux(use_rs2, rs2, 0.U)

  // 输出结果打包
  out.bits.pc := in.bits.pc
  out.bits.inst := in.bits.inst
  out.bits.robIdx := robAlloc.idx         // 分配到的 ROB 索引

  out.bits.src1Data := regRead.rdata1     // Dispatch 阶段读取的 rs1 值
  out.bits.src2Data := regRead.rdata2     // Dispatch 阶段读取的 rs2 值
  out.bits.imm := in.bits.imm
  out.bits.type_decode_together := in.bits.type_decode_together
  out.bits.predict_taken := in.bits.predict_taken
  out.bits.predict_target := in.bits.predict_target
  out.bits.bht_meta := in.bits.bht_meta
  out.bits.regWriteEnable := regWriteEnable

  in.ready := out.ready && canDispatch    // 反压逻辑
  out.valid := in.valid && canDispatch && !flush // 输出有效性。flush 时 out.valid 拉低 不向下游流水级输出
}
