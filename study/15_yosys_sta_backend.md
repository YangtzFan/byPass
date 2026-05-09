# 15. 后端流程：基于 yosys-sta 的综合 / 时序 / 功耗分析

> 本工程已将 [`OSCPU/yosys-sta`](https://github.com/OSCPU/yosys-sta) 的脚本集
> 直接内置到 `tools/yosys-sta/`（**不是 git 子模块**），并在根目录
> `xmake.lua` 中提供 `sta-init` / `sta-syn` / `sta` / `sta-clean` 四个 target，
> 完整替代原仓库的 `Makefile` 功能。本文档系统介绍：
>
> - 工具链组成与目录布局
> - 一次性环境安装
> - byPass 顶层接线对应关系
> - 完整命令流程（基于 xmake）
> - **SDC 约束文件的详细写法（含 byPass 适配示例）**
> - 报告解读与频率扫描
> - FAQ

---

## 1. 工具链组成与目录布局

| 组件 | 作用 | 来源 |
|---|---|---|
| **Yosys** (≥0.48) | 将 Verilog/SV 综合为标准单元网表 | [oss-cad-suite][cad-suite] |
| **iEDA**（含 `iSTA`、`iPA`） | 静态时序分析 + 功耗分析 | `xmake run sta-init` 下载预编译二进制 |
| **icsprout55 PDK** | 开源 55nm 工艺库（liberty / 单元集） | `xmake run sta-init` 克隆 PDK 仓库 |

[cad-suite]: https://github.com/YosysHQ/oss-cad-suite-build/releases

```
byPass/
├── xmake.lua                        ⭐ 提供 sta-init / sta-syn / sta / sta-clean
├── tools/
│   └── yosys-sta/                   后端脚本与运行时根目录（已内置入仓）
│       ├── scripts/
│       │   ├── yosys.tcl            Yosys 综合主脚本（DELAY 4 策略 + ABC 流程）
│       │   ├── sta.tcl              iSTA 入口：read_netlist / liberty / sdc → report
│       │   ├── common.tcl           通用配置（按 PDK 分发）
│       │   ├── default.sdc          默认 SDC（按环境变量 create_clock）
│       │   └── pdk/
│       │       ├── icsprout55.tcl   55nm PDK 路径与 lib 选择
│       │       └── nangate45.tcl    nangate45 备用配置
│       ├── example/                 GCD 示例设计（用于自检）
│       ├── bin/                     ★ .gitignore — sta-init 后含 iEDA 二进制
│       ├── pdk/                     ★ .gitignore — sta-init 后含 icsprout55-pdk
│       └── result/                  ★ .gitignore — 直接调脚本时的默认输出目录
└── build/
    ├── rtl/                         Chisel 产物：MyCPU.sv 等（综合输入）
    └── sta/                         ⭐ xmake `O` 默认输出：综合 + STA 报告
        └── MyCPU-500MHz/
            ├── MyCPU.netlist.v      综合后门级网表
            ├── MyCPU.netlist.v.sim  网表仿真版（带 cell 模型）
            ├── synth_stat.txt       面积报告
            ├── synth_check.txt      综合 check（含意外 latch 等）
            ├── yosys.log            yosys 完整日志
            ├── MyCPU.rpt            iSTA 时序：WNS / TNS / 关键路径
            ├── MyCPU.cap/.fanout/.trans   电容/扇出/转换违例
            ├── MyCPU_setup.skew     setup 时钟偏斜
            ├── MyCPU_hold.skew      hold 时钟偏斜
            ├── MyCPU.pwr            iPA 整体功耗
            ├── MyCPU_instance.pwr   单元级功耗
            ├── MyCPU_instance.csv   单元级功耗（CSV）
            └── sta.log              iSTA 完整日志
```

---

## 2. 一次性环境安装

### 2.1 安装 Yosys（≥0.48）

下载 oss-cad-suite，将其 `bin` 加入 `PATH`：

```bash
export PATH=/path/to/oss-cad-suite/bin:$PATH
yosys -V        # 校验版本 ≥ 0.48
```

### 2.2 安装系统依赖（iEDA 运行时）

```bash
sudo apt install libunwind-dev liblzma-dev
# 或 RPM 系：sudo yum install libunwind liblzma
```

### 2.3 拉取 iEDA 二进制 + icsprout55 PDK

```bash
xmake run sta-init
```

该 target 等价于原 Makefile `make init`：

1. 通过 `wget … init-yosys-sta.sh` 下载预编译 iEDA 至 `tools/yosys-sta/bin/iEDA`；
2. `git clone -b ysyx … icsprout55-pdk` 至 `tools/yosys-sta/pdk/icsprout55/`；
3. `echo exit | ./bin/iEDA -v` 自检版本号。

> 需要已配置 GitHub SSH key 才能 clone PDK；架构非 x86_64 或库版本不匹配
> 时，参考 yosys-sta 上游 README 自行编译 iEDA 后将二进制放入 `bin/`。

---

## 3. byPass 顶层接线（用于跑后端）

| 项 | 值 | 来源 |
|---|---|---|
| 顶层模块 | `MyCPU` | `src/main/scala/MyCPU.scala:28` |
| 时钟端口 | `clock` | Chisel `Module` 默认端口 |
| 复位端口 | `reset` | 同上（同步高有效，由 Chisel 默认生成） |
| RTL 文件 | `build/rtl/*.sv` | `xmake run rtl` 产物 |
| 顶层 SV | `build/rtl/MyCPU.sv` | 同上 |

> ⚠️ byPass 的时钟端口名是 **`clock`**（Chisel 默认），不是 yosys-sta 默认的 `clk`，
> 调用 `sta` 时必须传 `CLK_PORT_NAME=clock`，否则 SDC 找不到端口报错。

---

## 4. 完整命令流程

### 4.1 生成 RTL

```bash
xmake run rtl              # 输出 build/rtl/*.sv
```

### 4.2 跑综合 + STA + 功耗

```bash
CLK_PORT_NAME=clock CLK_FREQ_MHZ=500 xmake run sta
```

- 内部按依赖先跑 `sta-syn`（yosys 综合）→ 再跑 `sta`（iSTA / iPA）；
- 默认 `DESIGN=MyCPU`，`PDK=icsprout55`，`O=build/sta`，`SDC_FILE=tools/yosys-sta/scripts/default.sdc`；
- 默认 `RTL_FILES = build/rtl/*.sv` 自动展开。

可覆盖的环境变量（与原 Makefile 等价）：

| 变量 | 默认值 | 含义 |
|---|---|---|
| `DESIGN` | `MyCPU` | 顶层模块名 + 结果目录前缀 |
| `PDK` | `icsprout55` | 选用 `scripts/pdk/<PDK>.tcl` |
| `CLK_PORT_NAME` | `clock` | SDC `create_clock` 目标端口 |
| `CLK_FREQ_MHZ` | `500` | 目标频率，影响 SDC 周期 |
| `SDC_FILE` | `tools/yosys-sta/scripts/default.sdc` | 自定义 SDC 路径 |
| `RTL_FILES` | `build/rtl/*.sv` | 空格分隔的 SV 列表 |
| `O` | `build/sta` | 结果输出根目录 |

### 4.3 仅综合（不做 STA）

```bash
CLK_PORT_NAME=clock xmake run sta-syn
```

### 4.4 清理

```bash
xmake run sta-clean        # 仅清后端产物（build/sta + tools/yosys-sta/result）
xmake run clean            # 清整个 build/（含 RTL）
```

### 4.5 频率扫描

```bash
for f in 300 400 500 600 700; do
  CLK_PORT_NAME=clock CLK_FREQ_MHZ=$f xmake run sta
done
# 各档结果落到 build/sta/MyCPU-<f>MHz/
```

---

## 5. SDC 约束文件详解

SDC（Synopsys Design Constraints）告诉 STA 工具：时钟周期、I/O 时序、伪
路径等。yosys-sta 默认提供 `scripts/default.sdc`，仅描述时钟。若要做严肃
的时序闭环，应针对 byPass 写专用 SDC。

### 5.1 默认 SDC 解析（`scripts/default.sdc`）

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

# 3) 创建主时钟，周期 = 1000 / freq (ns)，IO 比例 0.2
set clk_io_pct 0.2
set clk_port [get_ports $CLK_PORT_NAME]
create_clock -name core_clock \
             -period [expr 1000.0 / $CLK_FREQ_MHZ] \
             $clk_port
```

只有 `create_clock`，没有 I/O 延迟、伪路径、最大转换时间等约束。对 byPass
这种带 AXI / DRAM / IROM 端口的核心，结果会被 reset 路径或外部信号干扰，
建议用下面的 byPass 专用 SDC。

### 5.2 byPass 专用 SDC 模板

新建 `tools/yosys-sta/scripts/mycpu.sdc`（任意路径都可，只要传给 `SDC_FILE`）：

```tcl
# ======================================================================
# byPass MyCPU 后端约束（icsprout55，500 MHz 起评）
# 端口名参考：build/rtl/MyCPU.sv
#   - clock                     主时钟
#   - reset                     同步复位
#   - io_*                      AXI / 调试 / 中断等外部接口（具体名见顶层）
# ======================================================================

# ---------- 1) 时钟 ----------
set CLK_PORT_NAME clock
set CLK_FREQ_MHZ  500
if {[info exists env(CLK_PORT_NAME)]} { set CLK_PORT_NAME $::env(CLK_PORT_NAME) }
if {[info exists env(CLK_FREQ_MHZ)]}  { set CLK_FREQ_MHZ  $::env(CLK_FREQ_MHZ)  }

set CLK_PERIOD_NS [expr 1000.0 / $CLK_FREQ_MHZ]
set clk_port [get_ports $CLK_PORT_NAME]

# 主时钟
create_clock -name core_clock -period $CLK_PERIOD_NS $clk_port

# 时钟不确定度（jitter + skew margin），约 5% 周期
set_clock_uncertainty -setup [expr $CLK_PERIOD_NS * 0.05] [get_clocks core_clock]
set_clock_uncertainty -hold  [expr $CLK_PERIOD_NS * 0.02] [get_clocks core_clock]

# 时钟过渡时间上限（CTS 前理想时钟，给 0.1 ns 左右）
set_clock_transition 0.1 [get_clocks core_clock]

# ---------- 2) 复位伪路径 ----------
# reset 是异步信号（或同步释放），不参与时序闭环
set_false_path -from [get_ports reset]

# ---------- 3) 输入 / 输出延迟 ----------
# 假设外部接口寄存器侧给 30% 周期作为 IO 余量
set io_delay [expr $CLK_PERIOD_NS * 0.30]

# 所有 io_* 输入端口（除 clock/reset 外）均按 io_delay 约束
set ext_inputs  [remove_from_collection [all_inputs]  $clk_port]
set ext_inputs  [remove_from_collection $ext_inputs   [get_ports reset]]
set ext_outputs [all_outputs]

set_input_delay  -clock core_clock $io_delay $ext_inputs
set_output_delay -clock core_clock $io_delay $ext_outputs

# ---------- 4) 设计规则 ----------
# 单元最大转换时间 / 最大扇出（与 PDK 规则配合）
set_max_transition 0.30 [current_design]
set_max_fanout     24   [current_design]

# ---------- 5) 调试 / 仿真信号伪路径（可选） ----------
# 若顶层有 io_dbg_* 之类仅用于波形/打印的输出，可整体设伪路径
# set_false_path -to [get_ports {io_dbg_*}]
```

调用方式：

```bash
SDC_FILE=$PWD/tools/yosys-sta/scripts/mycpu.sdc \
CLK_PORT_NAME=clock CLK_FREQ_MHZ=500 \
xmake run sta
```

### 5.3 SDC 常用命令速查

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

### 5.4 找端口名的方法

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

---

## 6. 报告解读速查

### 6.1 时序闭环判定（`<DESIGN>.rpt`）

- **WNS (Worst Negative Slack) ≥ 0** → 当前频率收敛；
- **TNS (Total Negative Slack) < 0** → 存在未达频路径，逐条看；
- 关键路径中频繁出现的模块（如 `IssueArbitration`、`PRF` 读端口、
  `ROB` 提交逻辑）即下一步优化重点：拆段、加流水、降扇出。

### 6.2 面积 / 功耗

- `synth_stat.txt`：cell 数、等效门数、寄存器数；
- `MyCPU.pwr`：动态 + 静态总功耗；
- `MyCPU_instance.csv`：单元级功耗（CSV，可导入 pandas/spreadsheet 做分析）。

### 6.3 违例排查

| 报告 | 触发条件 | 修法 |
|---|---|---|
| `*.cap` | 输出总电容超 lib 上限 | 加 buf / 拆 fanout |
| `*.fanout` | 扇出超阈值（默认 24） | 同上 |
| `*.trans` | 转换时间（slew）违例 | 提升驱动 / 调 cap_load |
| `*.skew` | 时钟偏斜过大 | CTS 前一般可忽略，关注绝对值趋势 |
| `synth_check.txt` 出现 `inferred latch` / `multi-driver` | 综合警告 | 回 Chisel 修默认赋值或 `dontTouch` |

---

## 7. 与 byPass 工作流的衔接建议

1. **改 RTL 前**：跑一次 `xmake run sta` 记录基线（建议保存
   `synth_stat.txt` 与 `MyCPU.rpt` 头部到 `study/LOG.md`）；
2. **改 RTL 后**：
   - `xmake run rtl` 重生成 SV；
   - 进入 `../difftest`：`SIM=verilator xmake b rtl` / `b Core` /
     `r sim-basic` / `r sim-regressive`，回归通过；
   - 再跑 `xmake run sta`，对比 PPA 变化；
3. **路径优化**：若 WNS 在某固定模块反复出现，结合
   `study/13_wakeup_model.md` 与 `study/14_invariants_and_hazards.md`
   评估能否拆流水或合并调度；
4. **CI 集成**：可在回归脚本末尾追加 `xmake run sta` 并 grep `WNS`
   做性能回归门禁。

---

## 8. 常见问题（FAQ）

- **Q：`sta-init` 卡在 `git clone icsprout55-pdk`。**
  确认 GitHub SSH key 已配置；或将 `xmake.lua` 中 `sta-init` 的 URL
  改为 HTTPS。

- **Q：iSTA 报 `port 'clk' not found`。**
  未传 `CLK_PORT_NAME=clock`。byPass 顶层是 `clock`。

- **Q：yosys 报 `multiple drivers` / `inferred latch`。**
  查 `synth_check.txt`，多半是 Chisel `Wire` 未默认赋值或选择信号未覆盖；
  在源码用 `dontTouch` 或补 `default` 修复。

- **Q：综合后面积异常大。**
  检查是否把 `DRAM.sv` / `IROM.sv` 等仅仿真用模型也综合了；
  这些应作为黑盒或在综合前剔除（生产流程通常综合 `MyCPU` 子层而非
  含外存的顶层）。可通过 `RTL_FILES` 显式指定子集：

  ```bash
  RTL_FILES="$(ls build/rtl/*.sv | grep -vE '/(DRAM|IROM)' | tr '\n' ' ')" \
  xmake run sta
  ```

- **Q：找不到 iEDA。**
  报错 `未找到 iEDA：tools/yosys-sta/bin/iEDA` → 先 `xmake run sta-init`。

- **Q：怎么换工艺到 nangate45？**
  设置 `PDK=nangate45 xmake run sta`，前提是 `tools/yosys-sta/pdk/nangate45/`
  已就位（`scripts/pdk/nangate45.tcl` 默认存在，PDK 文件需自行准备）。

---

## 9. 参考链接

- yosys-sta 上游：<https://github.com/OSCPU/yosys-sta>
- iEDA：<https://github.com/OSCC-Project/iEDA>
- iSTA 源码：<https://github.com/OSCC-Project/iEDA/tree/master/src/operation/iSTA>
- iSTA 报告解读视频：<https://www.bilibili.com/video/BV1a14y1B7uz/?t=1006>
- oss-cad-suite：<https://github.com/YosysHQ/oss-cad-suite-build/releases>
- SDC 语法规范（OpenSTA 子集）：<https://github.com/parallaxsw/OpenSTA/blob/master/doc/SDC.md>
