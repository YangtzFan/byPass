# =====================================================================
# byPass MyCPU 后端约束 (SDC for iSTA / icsprout55)
# 基于 build/rtl/MyCPU.sv 顶层端口编写：
#   - clock                                    主时钟（唯一时钟）
#   - inst_addr_o / inst_i                     IROM 取指接口
#   - sqEnq_* / sqQuery_* / sqLoadAddr_* /     Store-Queue / DRAM 接口
#     sqLoadData_*
#   - io_commit_*                              仿真/调试用 commit 输出
# 注意：MyCPU 顶层 Chisel `extends Module` 会自动注入 clock 与 reset 两个端口，
#       生成的 MyCPU.sv 第 3-4 行可见 input clock, reset；reset 仅做异步初始化，
#       不参与时序闭环，因此下方将 reset 路径声明为 false_path。
# =====================================================================

# ---------- 1) 时钟 ----------
set CLK_PORT_NAME clock
set CLK_FREQ_MHZ  500
if {[info exists env(CLK_PORT_NAME)]} { set CLK_PORT_NAME $::env(CLK_PORT_NAME) }
if {[info exists env(CLK_FREQ_MHZ)]}  { set CLK_FREQ_MHZ  $::env(CLK_FREQ_MHZ)  }

set CLK_PERIOD_NS [expr 1000.0 / $CLK_FREQ_MHZ]
set clk_port      [get_ports $CLK_PORT_NAME]

# 主时钟
create_clock -name core_clock -period $CLK_PERIOD_NS $clk_port

# 时钟不确定度（jitter + skew margin）
set_clock_uncertainty -setup [expr $CLK_PERIOD_NS * 0.05] [get_clocks core_clock]
set_clock_uncertainty -hold  [expr $CLK_PERIOD_NS * 0.02] [get_clocks core_clock]

# CTS 前理想时钟过渡时间
set_clock_transition 0.10 [get_clocks core_clock]

# ---------- 2) I/O 延迟 ----------
# 假设外部接口寄存器侧给 30% 周期作为 I/O 余量
set io_delay [expr $CLK_PERIOD_NS * 0.30]

# iSTA 不支持 remove_from_collection 与 get_ports 通配，
# 且 yosys 经 splitnets 后总线被拆为 inst_i_0_ / inst_i_1_ ... 这种名字。
# 直接对所有输入/输出统一约束（clock 上的 input_delay 与时钟自身路径无关，
# 不会影响 STA 收敛）。
set_input_delay  -clock core_clock $io_delay [all_inputs]
set_output_delay -clock core_clock $io_delay [all_outputs]

# ---------- 3) reset 伪路径 ----------
# MyCPU 顶层有由 Chisel `extends Module` 自动注入的 reset 端口（异步释放，
# 不参与时序闭环），将其声明为 false_path 以避免 STA 误报 setup/hold 违例。
set_false_path -from [get_ports reset]

# 如需手工放松某 io_commit_* 输出，按精确名字添加：
#   set_false_path -to [get_ports io_commit_0_pc_0_]

# ---------- 4) 设计规则 ----------
# 单元最大转换时间 / 最大扇出（与 PDK 推荐 + yosys.tcl 内 max_FO 对齐）
set_max_transition 0.30 [current_design]
set_max_fanout     24   [current_design]
