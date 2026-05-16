# 15. 后端流程：基于 yosys-sta 的综合 / 时序 / 功耗分析

> 本工程已将 [`OSCPU/yosys-sta`](https://github.com/OSCPU/yosys-sta) 的脚本集
> 直接内置到 `tools-backend/`（**不是 git 子模块**），并在根目录
> `xmake.lua` 中提供 `sta-init` / `sta-syn` / `sta` / `sta-clean` 四个 target，
> 完整替代原仓库的 `Makefile` 功能。本文档系统介绍：
>
> - 工具链组成与目录布局
> - 一次性环境安装
> - byPass 顶层接线对应关系
> - 完整命令流程（基于 xmake）
> - 报告解读与频率扫描
> - FAQ
>
> **SDC 约束文件的详细写法**已迁移至
> [`16_backend_analysis_basics.md` §6](16_backend_analysis_basics.md#6-sdc-约束文件详解)。

---

## 1. 工具链组成与目录布局

| 组件 | 作用 | 来源 |
|---|---|---|
| **Yosys** (≥0.48) | 将 Verilog/SV 综合为标准单元网表 | [oss-cad-suite][cad-suite] |
| **iEDA**（含 `iSTA`、`iPA`） | 静态时序分析 + 功耗分析 | `xmake run sta-init` 下载预编译二进制 |
| **icsprout55 PDK** | 开源 55nm 工艺库（liberty / 单元集） | `xmake run sta-init` 克隆 PDK 仓库 |
| **sv2v** (≥0.0.13) | SystemVerilog → Verilog 预处理（剔除 yosys 不支持的语法） | `xmake run sta-init` 下载 GitHub release |

[cad-suite]: https://github.com/YosysHQ/oss-cad-suite-build/releases

```
byPass/
├── xmake.lua                        ⭐ 提供 sta-init / sta-syn / sta / sta-clean
├── tools-backend/                   后端脚本与运行时根目录（仅 scripts/ 与 example/ 入仓）
│   ├── scripts/                     ✓ 已入仓 — 综合 / STA / PDK 配置脚本
│   │   ├── yosys.tcl                Yosys 综合主脚本（DELAY 4 策略 + ABC 流程）
│   │   ├── sta.tcl                  iSTA 入口：read_netlist / liberty / sdc → report
│   │   ├── common.tcl               通用配置（按 PDK 分发）
│   │   ├── default.sdc              默认 SDC（按环境变量 create_clock）
│   │   ├── mycpu.sdc                ⭐ byPass 专用 SDC（reset false_path / I/O delay）
│   │   └── pdk/
│   │       └── icsprout55.tcl       55nm PDK 路径与 lib 选择
│   ├── example/                     ✓ 已入仓 — GCD 示例设计（用于自检）
│   ├── bin/                         ✗ .gitignore — sta-init 后含 iEDA + sv2v 二进制
│   ├── pdk/                         ✗ .gitignore — sta-init 后含 icsprout55-pdk
│   └── result/                      ✗ .gitignore — 直接调脚本时的默认输出目录
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

### 2.3 拉取 iEDA 二进制 + sv2v + icsprout55 PDK

工程刻意把这些大文件（iEDA ~35 MB、sv2v ~10 MB、PDK ~320 MB）放进
`.gitignore`，避免膨胀 git 仓库；首次使用前在本地工作区一次性下载：

```bash
xmake run sta-init
```

该 target 顺序执行：

1. `wget … ieda.tar.bz2 | tar xfj -` → 下载并解压预编译 iEDA 至
   `tools-backend/bin/iEDA`（直接拉取 tarball，避免上游 wrapper 脚本
   捎带下载本工程不使用的 nangate45 PDK）；
2. 从 `https://github.com/zachjs/sv2v/releases/download/v0.0.13/sv2v-Linux.zip`
   解压 sv2v 至 `tools-backend/bin/sv2v`；
3. `git clone -b ysyx --depth 1 git@github.com:openecos-projects/icsprout55-pdk.git`
   至 `tools-backend/pdk/icsprout55/`；
4. `echo exit | ./bin/iEDA -v` 自检 iEDA 版本号。

> 需要本机已配置 GitHub SSH key 才能 clone PDK（或把命令改为 HTTPS）。
> 若架构不是 x86_64 或库版本不匹配，参考 yosys-sta 上游 README 自行
> 编译 iEDA 后将二进制放入 `tools-backend/bin/iEDA`。
> sv2v 需要本机预装 `unzip`；wget 取不到时可以手动下载替换。

---

## 3. byPass 顶层接线（用于跑后端）

| 项 | 值 | 来源 |
|---|---|---|
| 顶层模块 | `MyCPU` | `src/main/scala/MyCPU.scala:28` |
| 时钟端口 | `clock` | Chisel `Module` 默认端口 |
| 复位端口 | `reset` | Chisel `extends Module` 自动注入；异步释放，不参与时序闭环 |
| RTL 文件 | `build/rtl/*.sv` | `xmake run rtl` 产物 |
| 顶层 SV | `build/rtl/MyCPU.sv` | 同上 |

`build/rtl/MyCPU.sv` 顶层端口列表（按方向归类）：

| 方向 | 端口 | 含义 |
|---|---|---|
| in  | `clock` | 唯一时钟（Chisel 默认） |
| in  | `reset` | 全局复位（Chisel 默认；SDC 中已声明为 false_path） |
| out | `inst_addr_o[13:0]` | IROM 取指地址 |
| in  | `inst_i[127:0]` | IROM 取回 4 条指令 |
| in/out | `sqEnq_*` | StoreQueue 入队握手（valid/ready + addr/mask/wstrb/wdata/storeSeq） |
| in/out | `sqQuery_{0,1}_*` | 两路 Load 查询 SQ（送 wordAddr/loadMask，收 fwdMask/fwdData） |
| in/out | `sqLoadAddr_*`, `sqLoadData_*` | DRAM/L1 数据访问通道 |
| out | `io_commit_*` | 仿真 difftest 用的 commit 观测口（不参与时序闭环） |

提取方法：

```bash
grep -E "^\s*(input|output)" build/rtl/MyCPU.sv
```

> ⚠️ byPass 的时钟端口名是 **`clock`**（Chisel 默认），不是 yosys-sta 默认的 `clk`，
> 调用 `sta` 时必须传 `CLK_PORT_NAME=clock`，否则 SDC 找不到端口报错。
>
> ℹ️ MyCPU 顶层有 `reset` 端口（Chisel `extends Module` 自动注入），SDC 里
> 用 `set_false_path -from [get_ports reset]` 把它排除出时序闭环即可。

---

## 4. 完整命令流程

### 4.1 一次性初始化（首次使用）

```bash
xmake run sta-init         # 下载 iEDA + sv2v + 克隆 icsprout55 PDK
```

### 4.2 生成 RTL

```bash
xmake run rtl              # 输出 build/rtl/*.sv
```

### 4.3 跑综合 + STA + 功耗

```bash
CLK_PORT_NAME=clock CLK_FREQ_MHZ=500 xmake run sta
```

- 内部按顺序：① **sv2v 预处理**（把 Chisel/firtool 输出的 SystemVerilog 转成
  yosys 兼容的 Verilog，输出到 `build/sta/<DESIGN>-<freq>MHz/<DESIGN>.sv2v.v`）
  → ② **yosys 综合** → ③ **iSTA / iPA**；
- 默认 `DESIGN=MyCPU`，`PDK=icsprout55`，`O=build/sta`，
  `SDC_FILE=tools-backend/scripts/mycpu.sdc`；
- 默认 `RTL_FILES = build/rtl/*.sv` 自动展开。

可覆盖的环境变量（与原 Makefile 等价）：

| 变量 | 默认值 | 含义 |
|---|---|---|
| `DESIGN` | `MyCPU` | 顶层模块名 + 结果目录前缀 |
| `PDK` | `icsprout55` | 选用 `scripts/pdk/<PDK>.tcl` |
| `CLK_PORT_NAME` | `clock` | SDC `create_clock` 目标端口 |
| `CLK_FREQ_MHZ` | `500` | 目标频率，影响 SDC 周期 |
| `SDC_FILE` | `tools-backend/scripts/mycpu.sdc` | 自定义 SDC 路径 |
| `RTL_FILES` | `build/rtl/*.sv` | 空格分隔的 SV 列表 |
| `O` | `build/sta` | 结果输出根目录 |

### 4.4 仅综合（不做 STA）

```bash
CLK_PORT_NAME=clock xmake run sta-syn
```

### 4.5 清理

```bash
xmake run sta-clean        # 仅清后端产物（build/sta + tools-backend/result）
xmake run clean            # 清整个 build/（含 RTL）
```

### 4.6 频率扫描

串行扫描（适合调试单档）：

```bash
for f in 300 400 500 600 700; do
  CLK_PORT_NAME=clock CLK_FREQ_MHZ=$f xmake run sta
done
# 各档结果落到 build/sta/MyCPU-<f>MHz/
```

**并行扫描**（推荐，已纳入 `xmake.lua` 的 `sta-parallel` target）：

```bash
# 默认扫 20..40MHz 共 21 档，JOBS=30
xmake run sta-parallel

# 自定义频率列表 + 并发度（大内存服务器：256 核 / 1 TB RAM 可上 200）
FREQS="20 25 30 35 40 50 80 100 200 500" JOBS=200 xmake run sta-parallel
```

实现要点（见 `xmake.lua` `sta-parallel` target）：

1. 每档结果按 `result_dir = <O>/<DESIGN>-<freq>MHz` 天然隔离，无写入冲突；
2. 用 GNU `parallel --halt soon,fail=0` 调度，单档失败不中断整批；
3. 每档完整日志落盘 `<O>/sta-<freq>.log`、状态写 `<O>/sta-<freq>.status`，
   便于事后回看；
4. 子进程显式 `cd "$script_dir"` 并继承 `XMAKE_GLOBALDIR` 等关键环境变量，
   避免 `xmake run sta` 找不到项目根；
5. 跑完打印 `PASS=N FAIL=M` 汇总表格；任一档失败终态非零。

> ⚠️ **资源提示**：每个 yosys 综合约 ≈ 60 min 单核 + 几 GB；iSTA 约 2 min；
> iPA 跑 OoO 设计的 wakeup 反馈环时图节点会膨胀到 30 M+，单档峰值内存可达 ~30 GB。
> 21 档同时跑 iPA 总内存约 600 GB，需在 ≥ 768 GB RAM 服务器上运行。
> 仅做频率扫描可以用 `sta-syn` + 单独跑 iSTA 来跳过 iPA（详见 §4.2）。

> 旧 shell 版 `scripts/run_sta_parallel.sh` 仅做了 21 档 yosys+iSTA+iPA 并行，
> 没有汇总、没有 `--halt soon,fail=0`、没有日志落盘到 `sta-<f>.log`，已被
> `xmake run sta-parallel` 完全替代，保留仅作历史参考。

---


## 5. 报告解读速查

> 📖 **更系统的入门讲解**（每一种报告"新手怎么看"）请阅
> [`16_backend_analysis_basics.md`](16_backend_analysis_basics.md)；
> 本节仅作运维侧的速查表。

### 5.1 时序闭环判定（`<DESIGN>.rpt`）

- **WNS (Worst Negative Slack) ≥ 0** → 当前频率收敛；
- **TNS (Total Negative Slack) < 0** → 存在未达频路径，逐条看；
- 关键路径中频繁出现的模块（如 `IssueArbitration`、`PRF` 读端口、
  `ROB` 提交逻辑）即下一步优化重点：拆段、加流水、降扇出。

### 5.2 面积 / 功耗

- `synth_stat.txt`：cell 数、等效门数、寄存器数；
- `MyCPU.pwr`：动态 + 静态总功耗；
- `MyCPU_instance.csv`：单元级功耗（CSV，可导入 pandas/spreadsheet 做分析）。

### 5.3 违例排查

| 报告 | 触发条件 | 修法 |
|---|---|---|
| `*.cap` | 输出总电容超 lib 上限 | 加 buf / 拆 fanout |
| `*.fanout` | 扇出超阈值（默认 24） | 同上 |
| `*.trans` | 转换时间（slew）违例 | 提升驱动 / 调 cap_load |
| `*.skew` | 时钟偏斜过大 | CTS 前一般可忽略，关注绝对值趋势 |
| `synth_check.txt` 出现 `inferred latch` / `multi-driver` | 综合警告 | 回 Chisel 修默认赋值或 `dontTouch` |

---

## 6. 与 byPass 工作流的衔接建议

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

## 7. 常见问题（FAQ）

- **Q：yosys 报 `Invalid nesting of always blocks and/or initializations`
  （`always_ff` 中出现 `automatic logic ...`）。**
  Chisel/firtool 输出的 SystemVerilog 在 `always_ff` 内使用了 `automatic`
  局部变量，yosys 0.4x ~ 0.6x 的内置 SV 解析器并不支持。
  本工程的 `xmake.lua` 已经在 `sta` / `sta-syn` 流程里**自动**调用
  `tools-backend/bin/sv2v` 把 `build/rtl/*.sv` 翻译成单个
  yosys 友好的 `<DESIGN>.sv2v.v` 再喂给 yosys，无需手工处理。
  如果 `tools-backend/bin/sv2v` 不存在，从
  <https://github.com/zachjs/sv2v/releases> 下载对应平台预编译包，
  把可执行文件放到 `tools-backend/bin/sv2v` 并 `chmod +x`。

- **Q：iEDA / sv2v / icsprout55 PDK 是否要单独下载？**
  需要，但只需一次。这些大文件刻意不入仓（避免 git 仓库膨胀），
  执行 `xmake run sta-init` 即会自动：① 下载预编译 iEDA、
  ② 解压 sv2v v0.0.13、③ `git clone` icsprout55 PDK。
  下载完成后 `tools-backend/bin/{iEDA,sv2v}` 与
  `tools-backend/pdk/icsprout55/` 都已就绪，可以直接跑 `xmake run sta`。

- **Q：`sta-init` 卡在 `git clone icsprout55-pdk`。**
  确认 GitHub SSH key 已配置；或将 `xmake.lua` 中 `sta-init` 内对应
  URL 改为 HTTPS。注意：xmake 子进程沙箱可能丢失 SSH/DNS 环境，本工程
  已改成直接调外部 `git clone`，仍失败时可以手动到
  `tools-backend/pdk/` 下 clone。

- **Q：iSTA 报 `port 'clk' not found`。**
  未传 `CLK_PORT_NAME=clock`，byPass 顶层是 `clock`，调用时必须显式覆盖。
  若是 `port 'reset' not found`，则说明 RTL 顶层确实没有 reset（非常规
  情形），从 SDC 中去掉对应那行即可——byPass 默认情况下 `reset` 端口存在。

- **Q：yosys 综合非常慢（>30 分钟还在 `share` 阶段）。**
  byPass 是乱序 CPU，组合逻辑庞大，yosys 的 `share -aggressive` 资源共享
  SAT 求解很耗时。本工程 `tools-backend/scripts/yosys.tcl` 已默认
  **关闭** 该步（约第 186 行注释掉），换取约 50 分钟综合时间，代价是
  共享带来的面积优化损失（实测 462k 实例 ~1.04 mm² @icsprout55）。
  如要恢复，把 `# share -aggressive` 取消注释即可。

- **Q：iSTA 报 `invalid command name "remove_from_collection"` /
  `"sizeof_collection"` / `get_ports xxx* was not found`。**
  iSTA 只实现了 SDC 命令的子集：
  - 不支持 `remove_from_collection` / `sizeof_collection`；
  - `get_ports` 不支持 `*` 通配；
  - 不支持多行花括号列表 `get_ports {a* b*}`。

  解决：用 `[all_inputs]` / `[all_outputs]` 一把约束（仓库自带的
  `mycpu.sdc` 就是这样写的），或者按 yosys splitnets 后的精确名字
  `inst_i_0_` 这种逐个 `set_input_delay`。

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
  报错 `未找到 iEDA：tools-backend/bin/iEDA` → 先 `xmake run sta-init`。

- **Q：yosys.log 太大（GB 级）。**
  本工程 `xmake.lua` 已经在 yosys 输出管道里加了 grep 过滤，丢弃
  `OPT_EXPR` / `AUTONAME` 阶段每个 cell 一行的逐项替换日志
  （`Replacing $_..._ cell`、`Rename cell $abc...`、
  `Removing wire ...` 等），把 ~1 GB 压到约 10 MB 量级；
  错误 / 警告行不会被过滤。如需完整原始日志，把 `xmake.lua` 里
  `grep -vE '^(...)'` 那段去掉即可。

- **Q：yosys 综合速度还能再快一点吗？**
  `xmake.lua` 调用 sv2v 时已加 `--top=MyCPU`，由 sv2v 在 SV→V 阶段
  做模块级死代码剔除，避免 yosys 加载未引用的子模块。如仍不够快，
  可考虑：① 关闭 `synth -flatten`；② 把 ABC 策略从 `DELAY 4` 改为
  `AREA 0`；③ 缩减 `RTL_FILES` 只综合纯 CPU 子层（去掉
  `DRAM.sv`/`IROM.sv` 等仿真模型）。

---

## 8. 参考链接

- yosys-sta 上游：<https://github.com/OSCPU/yosys-sta>
- iEDA：<https://github.com/OSCC-Project/iEDA>
- iSTA 源码：<https://github.com/OSCC-Project/iEDA/tree/master/src/operation/iSTA>
- iSTA 报告解读视频：<https://www.bilibili.com/video/BV1a14y1B7uz/?t=1006>
- oss-cad-suite：<https://github.com/YosysHQ/oss-cad-suite-build/releases>
- SDC 语法规范（OpenSTA 子集）：<https://github.com/parallaxsw/OpenSTA/blob/master/doc/SDC.md>
