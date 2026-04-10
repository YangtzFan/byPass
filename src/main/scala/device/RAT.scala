package mycpu.device

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// RAT（Renaming Alias Table）—— 投机态逻辑→物理寄存器映射表
// ============================================================================
// 32 个表项，每项存储一个物理寄存器编号（7 位），表示逻辑寄存器 x_i 当前映射到哪个物理寄存器
// 初始映射：x0->p0, x1->p1, ..., x31->p31
// x0 永远映射到 p0，不允许修改
//
// 端口配置：
//   - 4 个读端口（psrc1 查询）+ 4 个读端口（psrc2 查询）= 8 个组合读端口
//   - 4 个读端口用于读取旧映射 stalePdst（在分配 pdst 之前查询当前 RAT[rd]）
//   - 4 个写端口：Rename 阶段分配新物理寄存器后更新映射
//   - 1 个批量恢复端口：分支预测失败时从 checkpoint 恢复整个 RAT
//
// 注意：Rename 阶段的 4 条指令需要按从老到年轻的顺序处理，
// 以便更年轻的指令能看到更老的指令对 RAT 的更新（rename bypass）。
// 本模块内部不处理 rename bypass，由 Rename 模块在外部处理。
// ============================================================================
class RAT extends Module {
  val io = IO(new Bundle {
    // ---- 8 个组合读端口（Rename 阶段查询源操作数映射）----
    val raddr = Input(Vec(8, UInt(5.W)))                                // 读地址（逻辑寄存器编号）
    val rdata = Output(Vec(8, UInt(CPUConfig.prfAddrWidth.W)))          // 读数据（物理寄存器编号）

    // ---- 4 个旧映射读端口（Rename 阶段查询 stalePdst = RAT[rd]）----
    val staleRaddr = Input(Vec(4, UInt(5.W)))                           // 读地址（目的逻辑寄存器编号 rd）
    val staleRdata = Output(Vec(4, UInt(CPUConfig.prfAddrWidth.W)))     // 读数据（当前 RAT[rd] 映射的物理寄存器）

    // ---- 4 个写端口（Rename 阶段更新映射：RAT[rd] := pdst）----
    val wen   = Input(Vec(4, Bool()))                                   // 写使能（仅 rd != x0 且写回寄存器时）
    val waddr = Input(Vec(4, UInt(5.W)))                                // 写地址（逻辑寄存器编号 rd）
    val wdata = Input(Vec(4, UInt(CPUConfig.prfAddrWidth.W)))           // 写数据（新分配的物理寄存器编号 pdst）

    // ---- 批量恢复端口（分支预测失败时从 checkpoint 恢复）----
    val recover       = Input(Bool())                                   // 恢复使能
    val recoverData   = Input(Vec(CPUConfig.archRegs, UInt(CPUConfig.prfAddrWidth.W))) // 恢复数据（完整 RAT 快照）

    // ---- 完整快照输出端口（BranchCheckpoint 保存用）----
    val snapData = Output(Vec(CPUConfig.archRegs, UInt(CPUConfig.prfAddrWidth.W)))
  })

  // 映射表：32 个表项，初始映射 x_i -> p_i
  val table = RegInit(VecInit((0 until CPUConfig.archRegs).map(i => i.U(CPUConfig.prfAddrWidth.W))))

  // ---- 批量恢复逻辑（优先级最高）----
  when(io.recover) {
    for (i <- 0 until CPUConfig.archRegs) {
      table(i) := io.recoverData(i)
    }
  }.otherwise {
    // ---- 写入逻辑：Rename 阶段更新映射 ----
    // 按从老到年轻的顺序写入（lane 0 最老，lane 3 最年轻）
    // 如果同一逻辑寄存器被多条指令写入，最年轻的写入生效
    for (i <- 0 until 4) {
      when(io.wen(i) && io.waddr(i) =/= 0.U) {
        table(io.waddr(i)) := io.wdata(i)
      }
    }
  }

  // ---- 组合读端口：直接读取当前映射 ----
  // 注意：这里读到的是 "上一拍末尾" 的值，不包含本拍的写入
  // Rename bypass 由 Rename 模块在外部处理
  for (i <- 0 until 8) {
    io.rdata(i) := Mux(io.raddr(i) === 0.U, 0.U, table(io.raddr(i)))
  }

  // ---- 旧映射读端口：读取 RAT[rd] 作为 stalePdst ----
  for (i <- 0 until 4) {
    io.staleRdata(i) := Mux(io.staleRaddr(i) === 0.U, 0.U, table(io.staleRaddr(i)))
  }

  // ---- 完整快照输出（用于 BranchCheckpoint 保存）----
  io.snapData := table
}
