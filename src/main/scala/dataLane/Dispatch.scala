package mycpu.dataLane

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// Dispatch（派发阶段）—— 宽度 1
// ============================================================================
// 从 RenameBuffer 取出 1 条已分配 ROB 索引的指令，读取寄存器堆，
// 然后传给 Execute 阶段。
//
// 变更（相比之前）：
//   - 输入从 Decode_Dispatch_Payload 改为 RenameEntry（含 robIdx、regWriteEnable）
//   - ROB 分配已移至 Rename 阶段，Dispatch 不再分配 ROB
//   - Load-Use 冒险检测从 Decode 移至此处，检测到时对 RenameBuffer 施加背压
// ============================================================================
class Dispatch extends Module {
  val in  = IO(Flipped(Decoupled(new RenameEntry)))     // 输入：来自 RenameBuffer
  val out = IO(Decoupled(new Dispatch_Execute_Payload)) // 输出：派发结果
  val flush = IO(Input(Bool()))                         // 流水线冲刷信号

  // ---- 寄存器堆读取接口 ----
  val regRead = IO(new Bundle {
    val raddr1 = Output(UInt(5.W))   // 读 rs1 地址
    val raddr2 = Output(UInt(5.W))   // 读 rs2 地址
    val rdata1 = Input(UInt(32.W))   // rs1 数据
    val rdata2 = Input(UInt(32.W))   // rs2 数据
  })

  // ---- Load-Use 冒险检测接口 ----
  // 从 DispExDff 获取正在执行的指令信息，检测是否存在 Load-Use 数据依赖
  val hazard = IO(new Bundle {
    val ex_rd     = Input(UInt(5.W))    // Execute 阶段指令的目标寄存器编号
    val ex_isLoad = Input(Bool())       // 该指令是否为 Load
    val ex_valid  = Input(Bool())       // 该流水级是否有有效指令
  })

  // ---- 指令字段提取 ----
  val inst = in.bits.inst
  val rs1  = inst(19, 15)
  val rs2  = inst(24, 20)

  // ---- 指令类型提取（从 9 位独热编码）----
  val td    = in.bits.type_decode_together
  val jalr  = td(6)
  val bType = td(5)
  val lType = td(4)
  val iType = td(3)
  val sType = td(2)
  val rType = td(1)

  // ---- Load-Use 冒险检测 ----
  // 当 DispExDff 中的指令是 Load，且当前指令的源寄存器依赖该 Load 的 rd 时，
  // 必须暂停一个周期，等待 Load 数据从 MEM 阶段通过旁路转发可用。
  val use_rs1 = jalr || bType || iType || sType || rType || lType
  val use_rs2 = bType || sType || rType
  val loadUseStall = hazard.ex_valid && hazard.ex_isLoad && (hazard.ex_rd =/= 0.U) &&
    ((use_rs1 && (rs1 === hazard.ex_rd)) ||
     (use_rs2 && (rs2 === hazard.ex_rd)))

  // ---- 寄存器堆读取 ----
  regRead.raddr1 := Mux(use_rs1, rs1, 0.U)
  regRead.raddr2 := Mux(use_rs2, rs2, 0.U)

  // ---- 输出结果打包 ----
  out.bits.pc                   := in.bits.pc
  out.bits.inst                 := in.bits.inst
  out.bits.robIdx               := in.bits.robIdx            // 由 Rename 阶段分配
  out.bits.src1Data             := regRead.rdata1            // Dispatch 阶段读取的 rs1 值
  out.bits.src2Data             := regRead.rdata2            // Dispatch 阶段读取的 rs2 值
  out.bits.imm                  := in.bits.imm
  out.bits.type_decode_together := td
  out.bits.predict_taken        := in.bits.predict_taken
  out.bits.predict_target       := in.bits.predict_target
  out.bits.bht_meta             := in.bits.bht_meta
  out.bits.regWriteEnable       := in.bits.regWriteEnable    // 由 Rename 阶段计算

  // ---- 握手信号 ----
  // Load-Use 停顿时：不从 RenameBuffer 消费、不向 Execute 输出（插入气泡）
  // flush 时：抑制 out.valid
  in.ready  := out.ready && !loadUseStall
  out.valid := in.valid && !loadUseStall && !flush
}
