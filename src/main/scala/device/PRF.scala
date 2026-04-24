package mycpu.device

import chisel3._
import chisel3.util._
import mycpu.CPUConfig

// ============================================================================
// PRF（Physical Register File）—— 物理寄存器堆（参数化多端口版本）
// ============================================================================
// 深度 128（p0~p127），数据宽度 32 位
// p0 硬编码为 0：写入 p0 的操作被忽略，读取 p0 总是返回 0
//
// 端口配置（按 CPUConfig 参数化）：
//   - prfWritePorts 个写端口：由 Refresh 阶段 Vec 写回驱动（每 lane 一个）
//   - prfReadPorts  个读端口：由 ReadReg 阶段驱动（每 lane 2 个源操作数）
//
// 写优先旁路：当同一周期多个写端口与读端口命中同一物理寄存器时，
//   按写端口索引从低到高优先，返回最高优先的写数据，避免读到旧值。
//   实际的多 lane 后端保证同拍不会有两个写端口 targeting 同一 pdst（乱序分配唯一），
//   但仍然通过 PriorityMux 做健壮兜底。
// ============================================================================
class PRF extends Module {
  private val wPorts = CPUConfig.prfWritePorts
  private val rPorts = CPUConfig.prfReadPorts

  val io = IO(new Bundle {
    // ---- 写端口 Vec（Refresh 各 lane 写回执行结果）----
    val wen   = Input(Vec(wPorts, Bool()))                              // 每端口写使能
    val waddr = Input(Vec(wPorts, UInt(CPUConfig.prfAddrWidth.W)))      // 每端口写地址
    val wdata = Input(Vec(wPorts, UInt(32.W)))                          // 每端口写数据

    // ---- 读端口 Vec（ReadReg 各 lane 读取源操作数）----
    val raddr = Input(Vec(rPorts, UInt(CPUConfig.prfAddrWidth.W)))      // 每端口读地址
    val rdata = Output(Vec(rPorts, UInt(32.W)))                          // 每端口读数据
  })

  // 128 个 32 位物理寄存器，初始化为 0
  val regFile = RegInit(VecInit(Seq.fill(CPUConfig.prfEntries)(0.U(32.W))))

  // ---- 写入逻辑 ----
  // p0 不可写（p0 硬编码为 0）。多写端口假设目标 pdst 唯一（乱序分配保证），
  // 若同拍不幸重复命中，顺序 for 循环的后一个会覆盖前一个。
  for (i <- 0 until wPorts) {
    when(io.wen(i) && io.waddr(i) =/= 0.U) {
      regFile(io.waddr(i)) := io.wdata(i)
    }
  }

  // ---- 读端口 Vec（带写优先旁路）----
  // 对每个读端口，优先匹配任何同拍写端口命中；p0 恒返回 0。
  for (r <- 0 until rPorts) {
    val ra = io.raddr(r)
    // 构造 "写端口→rdata" 的优先候选表，低索引优先
    val bypassCases = (0 until wPorts).map { w =>
      (io.wen(w) && io.waddr(w) =/= 0.U && io.waddr(w) === ra) -> io.wdata(w)
    }
    val bypassed = PriorityMux(bypassCases :+ (true.B -> regFile(ra)))
    io.rdata(r) := Mux(ra === 0.U, 0.U, bypassed)
  }
}
