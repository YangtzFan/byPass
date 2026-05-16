# 16. 后端分析入门：综合产物 / 时序报告 / 功耗与面积怎么读

> 配套阅读：[`15_yosys_sta_backend.md`](15_yosys_sta_backend.md)（如何把后端跑起来）。
> 本文目标：**完全没接触过后端的同学**也能看懂跑完 `xmake run sta` 之后
> 每一份报告，并自行判断设计当前的健康程度。
>
> 本文不要求你能写 SDC，只要求你能看懂别人写好的 SDC 跑出来的报告。

---

## 目录

1. [为什么要做后端分析](#1-为什么要做后端分析)
2. [一图看懂前端 → 后端](#2-一图看懂前端--后端)
3. [核心术语速查（必背）](#3-核心术语速查必背)
4. [产物文件全景](#4-产物文件全景)
5. [综合报告：面积 / 单元数怎么看](#5-综合报告面积--单元数怎么看)
6. [SDC 约束文件详解](#6-sdc-约束文件详解)
7. [STA 报告：时序闭环看 WNS / TNS / 路径](#7-sta-报告时序闭环看-wns--tns--路径)
8. [功耗报告：动态 / 静态 / 漏电](#8-功耗报告动态--静态--漏电)
9. [常见时序问题与定位手法](#9-常见时序问题与定位手法)
10. [byPass 当前基线（参考值）](#10-bypass-当前基线参考值)
11. [推荐学习路径](#11-推荐学习路径)

---

## 1. 为什么要做后端分析

写 RTL（Verilog / Chisel）只能保证**功能正确**——能仿真过，能跑测试用例。
但芯片是物理实体：

| 关心的事 | 仅做仿真能不能回答？ | 谁回答 |
|---|---|---|
| 我设计能跑多快？（最高时钟频率） | ❌ 不能 | **STA 静态时序分析** |
| 用了多少晶体管 / 多大面积？ | ❌ 不能 | **综合后的 stat 报告** |
| 一秒钟烧多少瓦电？ | ❌ 不能 | **功耗分析（PA）** |
| 关键路径在哪里？要不要拆 / 加流水？ | ❌ 不能 | **STA 的路径报告** |

所以"后端"的最大价值是：把"功能正确的 RTL"翻译成
**门级网表（gate-level netlist）**，再用 PDK（工艺库）中的真实电阻/电容/延迟
去算这块设计在硅片上的真实表现。

> 💡 byPass 用的是 **icsprout55**（中科院开源 55nm 工艺）+ **iEDA**（开源
> 后端工具集）+ **yosys/sv2v** 前端整理。完整链路见 doc 15。

---

## 2. 一图看懂前端 → 后端

```
┌─────────────┐  Chisel/SV    ┌─────────────┐  sv2v       ┌──────────────┐
│  RTL 源码   │ ───────────▶   │  build/rtl  │ ──────────▶ │  build/sta/  │
│ (.scala)    │   xmake b rtl │  *.sv       │  --top=MyCPU│  sv2v.v      │
└─────────────┘               └─────────────┘             └──────┬───────┘
                                                                 │ yosys 综合
                                                                 ▼
                                              ┌────────────────────────────┐
                                              │ MyCPU.syn.v   ← 门级网表    │
                                              │ synth_stat.txt ← 单元/面积  │
                                              │ synth_check.txt ← DRC 检查 │
                                              └────────────┬───────────────┘
                                                           │ iSTA 时序分析
                                                           ▼
                                              ┌────────────────────────────┐
                                              │ MyCPU.rpt ← 时序+功耗报告    │
                                              │   ↳ WNS / TNS / 关键路径    │
                                              │   ↳ Total Power            │
                                              └────────────────────────────┘
```

每一步的命令对应关系（见 doc 15 §4）：

| 命令 | 做什么 | 产生什么 |
|---|---|---|
| `xmake b rtl` | Chisel → SystemVerilog | `build/rtl/*.sv` |
| `xmake run sta-syn` | sv2v → 综合 | `build/sta/MyCPU.syn.v` 等 |
| `xmake run sta` | 综合 + STA | 上述 + `MyCPU.rpt` |

---

## 3. 核心术语速查（必背）

下面这些词在每份报告里都会反复出现，先建立感觉，后面看报告才不慌。

### 3.1 时序相关

| 术语 | 中文 | 一句话解释 |
|---|---|---|
| **Setup time** | 建立时间 | 数据必须在时钟上升沿**之前**稳定的最小时间 |
| **Hold time** | 保持时间 | 数据必须在时钟上升沿**之后**保持稳定的最小时间 |
| **Slack** | 时序裕量 | `要求时间 - 实际到达时间`，**正数=满足，负数=违例** |
| **WNS** | Worst Negative Slack | 全设计**最差**那条路径的 slack，越接近 0 越好 |
| **TNS** | Total Negative Slack | 所有违例路径的 slack 之和（负数），衡量违例**总量** |
| **Setup violation** | 建立时间违例 | 路径太长，数据来不及在时钟前就位 → 频率不能再高 |
| **Hold violation** | 保持时间违例 | 路径太短（少见于综合后，常见于布局后） |
| **Critical path** | 关键路径 | 时序余量最少的那条组合逻辑路径 |
| **Clock period** | 时钟周期 | 例如 500MHz → 2 ns；STA 用它做参考 |

> 🔑 **理解 slack 的物理意义**：
> 如果 WNS = -0.5 ns，意思是"这条路径还差 0.5 ns 走不完"。
> 你有两条出路：① 把时钟周期延长 0.5 ns（即降频）；
> ② 优化 RTL 把这条路径切短（流水化 / 简化逻辑）。

### 3.2 综合相关

| 术语 | 中文 | 解释 |
|---|---|---|
| **Cell / Instance** | 单元 / 实例 | 工艺库里的标准单元（与门、D 触发器…） |
| **Netlist** | 网表 | 用工艺库单元写的 Verilog（即综合产物） |
| **Liberty (.lib)** | 时序库 | PDK 提供的"每个单元的延迟/功耗"数据库 |
| **Cell area** | 单元面积 | 一个标准单元占的面积，单位 μm² |
| **Total area** | 总面积 | ≈ 所有 cell 面积之和（不含布线） |
| **Std cell** | 标准单元 | 最基础的库单元（INV / NAND / DFF …） |
| **Macro** | 宏单元 | 大块预制单元（SRAM / PLL），byPass 暂时不用 |

### 3.3 功耗相关

| 术语 | 中文 | 解释 |
|---|---|---|
| **Dynamic power** | 动态功耗 | 信号翻转（0↔1）消耗的能量，**和频率/活动率成正比** |
| **Static / Leakage** | 静态 / 漏电 | 即使没翻转也会漏掉的电流，和工艺/温度有关 |
| **Switching power** | 开关功耗 | 动态功耗里电容充放电那部分 |
| **Internal power** | 内部功耗 | 单元内部短路电流（在切换瞬间） |
| **Toggle rate** | 翻转率 | 一个信号每周期平均翻转多少次（0~1 之间） |

---

## 4. 产物文件全景

跑完 `xmake run sta` 后，在 `build/sta/` 下会看到大致这些文件
（具体名字以 `tools-backend/scripts/yosys.tcl` 与 `sta.tcl` 为准）：

```
build/sta/
├── sv2v.v              ← sv2v 把 .sv 拍平成传统 Verilog（输入）
├── MyCPU.syn.v         ← yosys 综合产生的【门级网表】（最重要）
├── MyCPU.syn.v.sim     ← 仿真专用版本（带初值，不参与 STA）
├── synth_stat.txt      ← 综合统计：cell 类型、数量、面积
├── synth_check.txt     ← 综合检查：是否有锁存器、未驱动信号等
├── MyCPU.rpt           ← iSTA 时序+功耗报告（最重要）
└── yosys.log           ← yosys 全过程日志（已过滤为 5MB 量级）
```

> 💡 **该看哪个？** 三件套最常用：`synth_stat.txt`（看面积）、
> `MyCPU.rpt`（看时序+功耗）、`synth_check.txt`（看有没有踩坑）。

---

## 5. 综合报告：面积 / 单元数怎么看

### 5.1 `synth_stat.txt` 解读

典型结构（节选自一次 byPass 综合）：

```
=== MyCPU ===
   Number of wires:               350421
   Number of wire bits:           420113
   Number of cells:              462380     ← 总单元数
     $_DFF_P_                     45120     ← D 触发器（保存状态）
     $_NAND_                      89001     ← NAND 门
     $_NOR_                       21430
     INVX1                        62100     ← 反相器（最小驱动力）
     ...
   Chip area for module '\MyCPU':  1042189.32   ← 单位 μm²
```

**新手怎么看**：

1. **`Number of cells`**：总规模量级。byPass 当前 ~46 万实例，属于"中等规模乱序核"水平。
2. **DFF 占比**：触发器越多 = 状态越多 = 内存/寄存器堆/流水线寄存器多。
   `45120 / 462380 ≈ 9.7%` 是合理范围（PRF + ROB + IQ + checkpoint 等）。
3. **`Chip area`**：除以 1e6 转成 mm²。byPass 现在约 **1.04 mm²**。
   作为对比：一颗 ARM Cortex-M0 在 55nm 大约 0.05 mm²，所以乱序 4 发射核
   的"成本"是它的 ~20 倍。这个数字本身没"对错"，只是给你一个量级感。

### 5.2 `synth_check.txt` 解读

这份文件是"自检报告"，**理想情况下应该几乎为空**。常见告警：

| 告警关键词 | 含义 | 严重程度 |
|---|---|---|
| `found N problems in 'check'` | 总览，N=0 最好 | ⚠️ |
| `unused output` | 端口没驱动到任何东西 | 🟡 一般可忽略 |
| `latch` / `inferred latch` | 推断出锁存器（**很坏！**） | 🔴 必须修 |
| `multiple drivers` | 同一根线被两个东西驱动 | 🔴 必须修 |

> 🔥 **为什么锁存器这么糟糕**？
> 锁存器（latch）和触发器（DFF）不一样：它是**电平敏感**的，
> 在 STA 中很难分析、容易引入毛刺，也几乎一定意味着你写的
> `case`/`if-else` 里漏写了某个分支。Chisel 一般不会生成锁存器；
> 如果出现，多半是手写 SV 或 sv2v 处理出问题。

---

## 6. SDC 约束文件详解

看完综合报告（面积、单元）后，下一步要看时序——但在看 STA 报告前，
**必须先理解告诉 STA "什么算违例" 的那份文件**：SDC（Synopsys Design
Constraints）。它定义时钟周期、I/O 延迟、伪路径等约束；STA 工具读 SDC
+ 网表 + liberty 后才能算出 slack。

> 💡 简单说：**SDC 是约束的输入，时序报告是分析的输出**。
> 如果 SDC 写错（比如时钟端口名拼错），STA 直接报错；
> 如果 SDC 写得太宽松，时序报告会看起来"很美"但流片必崩。

yosys-sta 默认提供 `tools-backend/scripts/default.sdc`（仅 `create_clock`），
而 byPass 实际用 `tools-backend/scripts/mycpu.sdc`（已通过 iSTA 端到端验证）。

### 6.1 默认 SDC 解析（`scripts/default.sdc`）

```tcl
# 1) 从环境变量读取时钟端口名（默认 clk，被 xmake 覆盖为 clock）
set CLK_PORT_NAME clk
if {[info exists env(CLK_PORT_NAME)]} {
  set CLK_PORT_NAME $::env(CLK_PORT_NAME)
}

# 2) 从环境变量读取目标频率（MHz）
set CLK_FREQ_MHZ 500
if {[info exists env(CLK_FREQ_MHZ)]} {
  set CLK_FREQ_MHZ $::env(CLK_FREQ_MHZ)
}

# 3) 创建主时钟，周期 = 1000 / freq (ns)
set clk_io_pct 0.2
set clk_port [get_ports $CLK_PORT_NAME]
create_clock -name core_clock \
             -period [expr 1000.0 / $CLK_FREQ_MHZ] \
             $clk_port
```

只有 `create_clock`，没有 I/O 延迟、伪路径、最大转换时间等约束。对 byPass
这种带 AXI / DRAM / IROM 端口的核心，结果会被 reset 路径或外部信号干扰，
建议用下面的 byPass 专用 SDC。

### 6.2 byPass 专用 SDC 模板

仓库已自带 `tools-backend/scripts/mycpu.sdc`（`xmake run sta` 默认就用这份），
经 iSTA 端到端验证可跑通。完整内容如下：

```tcl
# =====================================================================
# byPass MyCPU 后端约束 (SDC for iSTA / icsprout55)
# =====================================================================

# ---------- 1) 时钟 ----------
set CLK_PORT_NAME clock
set CLK_FREQ_MHZ  500
if {[info exists env(CLK_PORT_NAME)]} { set CLK_PORT_NAME $::env(CLK_PORT_NAME) }
if {[info exists env(CLK_FREQ_MHZ)]}  { set CLK_FREQ_MHZ  $::env(CLK_FREQ_MHZ)  }

set CLK_PERIOD_NS [expr 1000.0 / $CLK_FREQ_MHZ]
set clk_port      [get_ports $CLK_PORT_NAME]

create_clock -name core_clock -period $CLK_PERIOD_NS $clk_port

set_clock_uncertainty -setup [expr $CLK_PERIOD_NS * 0.05] [get_clocks core_clock]
set_clock_uncertainty -hold  [expr $CLK_PERIOD_NS * 0.02] [get_clocks core_clock]
set_clock_transition 0.10 [get_clocks core_clock]

# ---------- 2) I/O 延迟（30% 周期作为外部接口余量） ----------
# iSTA 不支持 remove_from_collection / get_ports 通配，且 yosys 经
# splitnets 后总线被拆为 inst_i_0_ / inst_i_1_ ... 这种名字，因此对所有
# 输入/输出统一约束（clock 上的 input_delay 与时钟自身路径无关，无副作用）。
set io_delay [expr $CLK_PERIOD_NS * 0.30]
set_input_delay  -clock core_clock $io_delay [all_inputs]
set_output_delay -clock core_clock $io_delay [all_outputs]

# ---------- 3) reset 伪路径 ----------
# MyCPU 顶层有 Chisel `extends Module` 自动注入的 reset 端口（异步释放，
# 不参与时序闭环），将其声明为 false_path 以避免 STA 误报。
set_false_path -from [get_ports reset]

# ---------- 4) 设计规则 ----------
set_max_transition 0.30 [current_design]
set_max_fanout     24   [current_design]
```

要点（**踩过的坑** 都在注释里）：

- **iSTA 的 `get_ports` 不支持 `*` 通配**，因此不能写 `get_ports {io_*}`；
  yosys 的 `splitnets -format __v` 又把总线拆成单 bit 端口
  （`inst_i_0_` / `inst_i_1_` …），逐个枚举工作量很大；最稳的方式就是
  `[all_inputs] / [all_outputs]` 一把约束；
- **iSTA 不支持 `remove_from_collection` / `sizeof_collection`** 等
  OpenSTA 高级集合命令，需用 `foreach` 或直接 `[all_*]` 替代；
- `MyCPU.sv` 顶层有由 Chisel `extends Module` 自动注入的 `reset` 端口
  （SV 文件第 4 行可见 `input reset`），SDC 里用
  `set_false_path -from [get_ports reset]` 将其排除出时序闭环即可。

调用方式（默认就是它，显式给出仅作示例）：

```bash
SDC_FILE=$PWD/tools-backend/scripts/mycpu.sdc \
CLK_PORT_NAME=clock CLK_FREQ_MHZ=500 \
xmake run sta
```

### 6.3 SDC 常用命令速查

| 命令 | 用途 | byPass 示例 |
|---|---|---|
| `create_clock -name N -period P [get_ports clk]` | 定义主时钟 | 必备 |
| `create_generated_clock` | 衍生分频时钟 | 暂未使用（byPass 单时钟域） |
| `set_clock_uncertainty` | 加 jitter/skew 余量 | 5% 周期起步 |
| `set_clock_transition` | 时钟边沿过渡时间 | CTS 前给理想 0.1 ns |
| `set_input_delay -clock C v [ports]` | 输入信号在时钟 C 上 v ns 后到达 | AXI 输入 30% 周期 |
| `set_output_delay -clock C v [ports]` | 输出需在时钟 C 后 v ns 内被采样 | AXI 输出 30% 周期 |
| `set_false_path -from / -to` | 伪路径，不做 STA | reset、调试输出 |
| `set_multicycle_path -setup N` | 多周期路径 | 罕用，确保有打拍逻辑 |
| `set_max_transition` | 全局最大转换时间 | 与 PDK 推荐一致 |
| `set_max_fanout` | 全局最大扇出 | yosys.tcl 已用 24 |
| `set_load` | 输出端等效负载 | 默认由综合脚本设置 |
| `set_driving_cell` | 输入端驱动单元 | 默认 BUFX0P5H7L |

### 6.4 找端口名的方法

实际端口名（特别是 AXI / IROM 接口）由 Chisel `IO(...)` 决定，下面任一
方式可查到：

```bash
# 方式 A：直接 grep 顶层 SV
grep -n "^\s*input\|^\s*output" build/rtl/MyCPU.sv

# 方式 B：综合后由 yosys 报出
grep -nE "^module MyCPU\b|input |output " build/sta/MyCPU-500MHz/MyCPU.netlist.v
```

写 SDC 时建议使用模式匹配，例如：

```tcl
set_input_delay  -clock core_clock $io_delay [get_ports {io_axi_*_ready io_axi_*_valid io_axi_*_bits_*}]
set_output_delay -clock core_clock $io_delay [get_ports {io_axi_*}]
```

> ⚠️ iSTA 对 SDC 的命令子集支持范围以 [iSTA 源码][ista-src] 为准，
> 若用到 OpenSTA 高级语法被忽略，会有 warning 但不报错。

[ista-src]: https://github.com/OSCC-Project/iEDA/tree/master/src/operation/iSTA

> 🔗 现在你已经知道 STA "看的是什么"，下一节就来读 STA "写出了什么"。

---

## 7. STA 报告：时序闭环看 WNS / TNS / 路径

`MyCPU.rpt` 是 **iSTA** 写的时序报告。它不是单一格式，常见会包含多段。

### 7.1 看总览（最先看的部分）

通常报告开头会有类似：

```
Clock: core_clock     Period: 2.00 ns   Frequency: 500 MHz
WNS (worst negative slack):  -0.500 ns
TNS (total negative slack):  -28.13 ns
Number of violating paths:   126
```

**怎么读**：

- WNS = -0.5 ns：在 500MHz 跑不动，**真实最高频率 ≈ 1 / (2 + 0.5) ns = 400 MHz**。
- TNS = -28.13 ns：违例总量大，说明问题不止一条孤立路径，是**整体逻辑过深**。
- 126 条违例路径：路径多 → 大概率是某个公共结构（如 issue queue 仲裁、
  bypass 网络）导致一类相似路径全都临界。

> 📐 **粗略换算最高频率**：`F_max ≈ 1 / (T_target - WNS)`。
> WNS 为负时除数变大，频率变小；为正时除数变小，频率变大。

### 7.2 看关键路径（找瓶颈）

`report_timing -max_path 5` 默认输出最差 5 条路径，每条形如：

```
Startpoint:  u_iq/dff_entry_3_pri_q   (rising edge clocked by core_clock)
Endpoint:    u_pdst_writeport_2_q     (rising edge clocked by core_clock)
Path Group:  core_clock
Path Type:   max (setup)

  Point                                Incr     Path
  ---------------------------------------------------
  clock core_clock (rise edge)         0.000    0.000
  clock network delay (ideal)          0.000    0.000
  u_iq/dff_entry_3_pri_q/CK            0.000    0.000 r
  u_iq/dff_entry_3_pri_q/Q (DFFRX1)    0.180    0.180 f
  u_alu/g123/Y (NAND2X1)               0.092    0.272 r
  u_alu/g456/Y (XOR2X2)                0.105    0.377 r
  ... 共 87 级 ...
  u_pdst_writeport_2_q/D               0.000    2.480 f
  data arrival time                             2.480

  clock core_clock (rise edge)         2.000    2.000
  clock uncertainty                   -0.050    1.950
  library setup time                  -0.030    1.920
  data required time                            1.920

  data required time                            1.920
  data arrival time                            -2.480
  -------------------------------------------------------
  slack (VIOLATED)                             -0.560
```

**新手看这种报告的三步法**：

1. **看 Startpoint / Endpoint**：哪个寄存器到哪个寄存器？
   上例：从 issue queue 第 3 项 → physical RF 写口 2。
   → 立刻知道是 **issue 仲裁→执行→写回** 这条路径。
2. **数中间一共多少级（行数）**：每行是一级门。87 级太深了，正常后端
   核心路径目标在 **20–30 级以内**。
3. **看 slack 末行**：负数=违例，绝对值就是"还差多少 ns"。

### 7.3 路径分类（不同根因不同治法）

| 路径类型 | 表现 | 治法 |
|---|---|---|
| **寄存器→组合→寄存器** | 中间几十级门 | 拆流水（加一级寄存器） |
| **寄存器→大片仲裁/选择→寄存器** | 出现大量 MUX/NAND 树 | 简化仲裁（如 round-robin → priority） |
| **跨模块 bypass 长链** | 走了多级模块层级 | 收紧 bypass 接线，去掉冗余级 |
| **memory/regfile 输出 → 远端组合** | 起点是 RAM/PRF 读口 | 在 RAM 输出加一级寄存器 |

byPass 目前 WNS 大约 -29 ns @500MHz（见 doc 15 §6），瓶颈集中在
**Issue → Execute → Bypass / Writeback** 这条链路。

### 7.4 三个最常见的"看错"

1. **把 TNS 当 WNS**：TNS 是总和，能到几百 ns 也正常；不要被吓到。
2. **忽视 path group**：iSTA 会按时钟域分组，先看自己关心那个时钟。
3. **报告里出现 reset 端口路径**：byPass 已在 SDC 里把 reset 设为
   `false_path`，正常情况下不应该再出现在违例路径里。
   如果出现，回头查 SDC 是否生效。

---

## 8. 功耗报告：动态 / 静态 / 漏电

`report_power -toggle 0.1` 中的 `0.1` 是默认翻转率：假定每个信号
平均每周期翻转 10%。这是个"估计"，要更精确需要给 SAIF/VCD 文件。

典型输出：

```
Group               Internal    Switching    Leakage      Total       %
-------------------------------------------------------------------------
combinational      35.20 mW     78.40 mW    2.10 mW    115.70 mW  62.3%
sequential         15.80 mW     22.10 mW    0.60 mW     38.50 mW  20.7%
clock_network       0.00 mW     30.50 mW    0.00 mW     30.50 mW  16.4%
memory              0.00 mW      0.00 mW    1.00 mW      1.00 mW   0.6%
-------------------------------------------------------------------------
Total              51.00 mW    131.00 mW    3.70 mW    185.70 mW
```

**新手解读**：

- **Total ≈ 186 mW**：500MHz 一颗 byPass 大概烧 0.19 W，量级对头。
- **Switching 占比最大**：纯组合逻辑翻转。降功耗的主要思路是**门控时钟**
  （clock gating）+ **降低组合深度**。
- **Clock network 16%**：时钟树本身就要烧电，这是物理实现的常态。
- **Leakage 只占 2%**：在 55nm 这是合理的（先进工艺漏电会更高）。

> ⚠️ **toggle rate 0.1 偏乐观**：CPU 实际 toggle rate 在 0.15~0.25。
> 想要更可信的功耗数，**用真实仿真 VCD** 喂 iPA。本工程目前未做这一步。

---

## 9. 常见时序问题与定位手法

### 9.1 "降频也修不掉违例"

→ 路径里有**组合环（combinational loop）**。先看 `synth_check.txt`
是否报 `found cells that share input signals` 一类告警；
再看 RTL 是否写出了 `wire a = ...a...` 这样的反馈。

### 9.2 "WNS 突然变差很多"

最可能是**新加的逻辑没流水**。例如 byPass 早期把 issue 仲裁从单路扩到
双发射时，WNS 从 -10 ns 退化到 -29 ns。修复办法：
- 在仲裁结果与执行单元之间加一级 `RegNext`；
- 或把仲裁拆成 **request 阶段 / grant 阶段** 两级。

### 9.3 "面积突然涨一截"

最可能是**资源共享被关闭**。byPass 当前 `tools-backend/scripts/yosys.tcl`
注释掉了 `share -aggressive`（节省 30+ 分钟综合时间），代价是面积
**多出大约 5–10%**。如果你做面积优化研究，记得打开它再对比。

### 9.4 看不懂的实例名

yosys 综合后实例名往往很丑（`$auto$alumacc.cc:485:replace_alu$1234`
之类），byPass 已设置 `splitnets -format __v` + 保留模块层级，
所以**模块名仍是 RTL 中的名字**。
反查方法：拿实例名前缀（如 `u_alu/`）→ 找 RTL 中对应模块（ALU.scala）。

---

## 10. byPass 当前基线（参考值）

> 截至最近一次跑通的成绩，给你一个"正常"是什么样的参照系。

| 指标 | 数值 | 说明 |
|---|---|---|
| RTL 模块数 | ~50 | `build/rtl/*.sv` |
| 综合后 cell 数 | **469,199**（Phase A.2） | `synth_stat.txt`（A.1 为 457,447） |
| 综合后面积 | **~1.04 mm²** @icsprout55 55nm | `Chip area` = 1,039,183.60 µm²（Phase A.2） |
| 触发器数 | ~40,400 | seq 元件占面积 23.95% |
| 目标频率 | **500 MHz** | `mycpu.sdc` 中 `create_clock 2.0` |
| WNS @500MHz | **-29.594 ns**（Phase A.1 历史） | 远未时序闭环，处于**功能正确但不可流片**阶段 |
| TNS @500MHz | -416,633 ns（Phase A.1 历史） | 几乎所有 endpoint 都不闭合 |
| **实测可闭合频率** | **≈ 30 MHz**（pre-CTS, Phase A.2） | `sta-parallel` 扫 28..31MHz 得 WNS 零点；A.1 为 29.3 MHz |
| 综合时间 | **~42 min** | sv2v 加 `--top=MyCPU` 后 |
| 总功耗（toggle 0.1） | ~28.7 W（下界，iEDA bug 影响 21% cell） | 见 doc 17 §6 |

### 10.1 频率扫描曲线（来自 `xmake run sta-parallel`，Phase B 终版 RTL）

| Freq (MHz) | Path Delay (ns) | WNS (ns) | TNS-Setup (ns) | 闭合 |
|---:|---:|---:|---:|:---:|
| 28 | 31.632 | +2.252 | 0.000 | ✅ |
| 29 | 31.632 | +1.082 | 0.000 | ✅ |
| 30 | 31.632 | −0.010 | −0.052 | ⚠️（贴零临界） |
| **≈ 29.99** | **31.632** | **≈ 0** | **≈ 0** | **✅（Fmax）** |
| 31 | 31.632 | −1.031 | −288.080 | ❌ |

观察：

- 28..31 MHz 区间 yosys+ABC 把关键路径稳定到 **31.632 ns 的下界**（Phase A.2 为 31.378 ns；本轮回到 icache_on 终版后 qDepth 加深 50/1/50→200/4/200 把 FIFO 更新逻辑略撑长 0.254 ns）；
- WNS 在 30→31 MHz 之间过零，线性插值 **真实 Fmax ≈ 29.99 MHz**（30 MHz 实测 −0.010 ns 已贴零，2 个端点微违例 0.010 ns 可视作闭合临界）；
- 历史 20..40 MHz 21 档扫描（Phase A.1）保留于 git log；本轮仅做 28..31 MHz 局部精扫；
- 与 doc 17 §3 中 "500 MHz 目标下的 31.45 ns" 对比，差距 0.18 ns —— sta-parallel 稳态 PD 仍紧贴"500 MHz 目标极限优化结果"。

> 📌 **怎么使用这张表**：
> 你做了一次改动后再跑一遍 `xmake run sta` 或 `xmake run sta-parallel`，
> 把新数据对比这里。
> 单元数 ±5% 内、WNS 变好或不变 → ✅；
> WNS 退化 > 1 ns → 🟡 排查改动；
> 综合时间 > 60 min → 🔴 看是否引入了组合环或巨大 case 表。

---

## 11. 推荐学习路径

如果你想从"看报告"进阶到"会调时序"，按下面顺序读：

1. **[本文 doc 16]** → 建立词汇表与全局观（你现在在这里）。
2. **[doc 15]** → 把命令链路打通，能复现报告。
3. **[J. Bhasker《Static Timing Analysis for Nanometer Designs》]**
   （或中文版《静态时序分析》）—— STA 圣经，重点看 setup/hold、时钟域。
4. **OpenROAD / yosys 文档** —— 了解开源工具链每一步在做什么，
   看 `synth_stat.txt` / `MyCPU.rpt` 反过来推工具行为。
5. **拿一条 byPass 的关键路径动手优化** ——
   先在 RTL 里找出来，做实验：拆一级流水或简化仲裁，
   看 WNS 怎么变。这一步比读多少书都重要。

> 💪 **学习心态**：后端报告第一次看一定是"满屏都是没见过的字"；
> 别怕，先抓三个数字（`Number of cells` / `WNS` / `Total Power`），
> 把它们和你的 RTL 改动建立映射，过几次循环就有感觉了。

---

> 配套：
> - [doc 15 - yosys-sta 后端流程使用指南](15_yosys_sta_backend.md)
> - 命令入口：`xmake run sta-init` / `xmake run sta`
> - 主要产物：`build/sta/synth_stat.txt`、`build/sta/MyCPU.rpt`
