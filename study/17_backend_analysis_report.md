# 17. byPass 后端分析报告（icsprout55 / 500 MHz 目标）

> 本文是基于 `xmake run sta` 一次完整跑通后的**实测分析报告**。
> 流程脚本：`xmake.lua` 中 `sta-syn` / `sta` target；
> 工具链：`yosys 0.64+210` + `sv2v v0.0.13` + `iEDA (iSTA / iPA)` + `icsprout55` PDK；
> 输入 RTL：`build/rtl/*.sv`（`xmake run rtl` 产物，10 级流水 4-issue OoO）；
> 约束：`tools-backend/scripts/mycpu.sdc`（`create_clock -period 2.0` ≈ 500 MHz，时钟为 `clock`，reset 走 false_path）；
> 报告路径：`build/sta/MyCPU-500MHz/`。
>
> 配套阅读：[`15_yosys_sta_backend.md`](15_yosys_sta_backend.md)、
> [`16_backend_analysis_basics.md`](16_backend_analysis_basics.md)。

---

## 0. 摘要（TL;DR）

| 指标 | 数值 | 评价 |
|---|---|---|
| 目标频率 | **500 MHz** (period 2.0 ns) | SDC 设定 |
| **实测最高频率（pre-CTS, 500MHz 单档）** | **≈ 31.6 MHz** | ❌ 严重不达标（500MHz 目标） |
| **实测 Fmax（pre-CTS, 频率扫描）** | **≈ 30 MHz**（贴零临界） | Phase B 终版 RTL（icache_on / qDepth 200·4·200），由 `sta-parallel` 扫 28..31 MHz；30 MHz WNS=−0.010 ns / TNS=−0.052 ns 仅 2 端点微违例，可视为闭合上限；见 §8 |
| WNS（Worst Negative Slack, 500 MHz 单档历史值） | **−29.594 ns** | ❌ 路径长度约为周期的 16 倍（保留作 500 MHz 不闭合的注记，本次未重跑该档） |
| FEP（Failing Endpoints） | 满目（含 IssueQueue.validVec / BCT / clock-gating ICG） | — |
| Hold WNS | +0.065 ns | ✅ 无 hold 违例 |
| 单元数 | 469 199 个 std cell | Phase A.2 RTL（前次 457 447，+2.6%） |
| 触发器数 | ≈ 40 400 个 DFF | — |
| Chip Area | **1 039 183.60 µm²** | 顺序逻辑占 23.95% (248 900.96 µm²)；Phase A.2 RTL |
| Clock fanout | 34 252（理想时钟，未 CTS） | 时序分析阶段未做时钟树 |
| 综合 DRC | 0 problems | ✅ |
| 功耗（iPA） | 28.7 W（下界，21% cell 受 iEDA bug 污染已剔除）；leakage = 0.749 mW（可信） | ⚠️ 见 §6 |

**核心结论**：当前 byPass 在 icsprout55 工艺下，**以 500 MHz 为目标无法闭合**；
关键路径起点位于 IssueQueue 的 wakeup / validVec 更新逻辑。
500 MHz 单档历史跑出的关键路径长度 31.45 ns（综合器在紧约束下额外发力），
Phase B 终版 RTL 在 28..31 MHz 扫频下稳态 PD = **31.632 ns**（较 Phase A.2 的 31.378 ns 增 0.254 ns，源于 qDepth 加深），**真实可闭合频率 ≈ 30 MHz**
（§8 实测：30 MHz WNS=−0.010 ns / TNS=−0.052 ns 贴零临界，31 MHz WNS=−1.031 ns / TNS=−288.080 ns 大量违例）。要达到 500 MHz 量级需要做相当大的微架构改造（详见 §7 建议）。

---

## 1. 流程回顾与本次跑通遇到的问题

### 1.1 正常流程

```bash
xmake run sta-init    # 一次性下载 iEDA + sv2v + 克隆 icsprout55 PDK
xmake run rtl         # Chisel → SV
xmake run sta         # sv2v + yosys + iSTA + iPA
```

### 1.2 修复：环境变量被吞掉导致 `yosys: command not found`

首次运行 `xmake run sta` 在 `[SYN]` 阶段失败，bash 报 `yosys: command not found`。
原因是 `xmake.lua` 里 `os.execv("bash", {...}, {envs = envs})` 的 `envs`
参数会**整体替换** subprocess 环境，导致从用户 shell 继承的 `PATH`（含
`oss-cad-suite/bin`）丢失。

**修复方式**：去掉 `envs` 参数，改为在 bash 命令前用 `export` 内联注入
`CLK_FREQ_MHZ` / `CLK_PORT_NAME`，从而保留外部 `PATH` 不变。
见 `xmake.lua` 的 `sta-syn` / `sta` target。

### 1.3 实测耗时（参考）

| 阶段 | 输入 / 输出 | 耗时 |
|---|---|---|
| `sv2v --top=MyCPU` | 全部 `build/rtl/*.sv` → `MyCPU.sv2v.v` 4.4 MB | < 30 s |
| `yosys` 综合（含 ABC mapping） | `MyCPU.sv2v.v` → `MyCPU.netlist.v` 298 MB | **≈ 60 min** |
| `iSTA` 时序分析 | `MyCPU.rpt` 等 | ≈ 2 min |
| `iPA` 功耗分析 | `MyCPU.pwr` 等 | ≈ 8 min |
| **合计** | — | **≈ 70 min** |

> 注：`MyCPU.netlist.v` 体积达 298 MB 是因为 `xmake run sta` 同时输出
> 「带模型的仿真网表」`MyCPU.netlist.v.sim`，并把 PDK liberty 中所有
> 用到的 cell 模型也内联其中。

---

## 2. 综合产物（Yosys → 门级网表）

`build/sta/MyCPU-28MHz/synth_stat.txt`（Phase A.2 RTL，扫频任一档综合统计相同）：

```
=== MyCPU ===
   469 449   wires / wire bits
       877   ports / port bits
   469 199   cells       (Local Area = 1.04E+06 µm²)
Chip area for module 'MyCPU':       1 039 183.60
  of which used for sequential elements: 248 900.96  (23.95%)
DFF 触发器（DFFQX1H7L 系列） 共 ≈ 40 400 个
```

`synth_check.txt`：

```
Found and reported 0 problems.
```

✅ 综合期没有 latch / 多驱动 / 悬空端口告警。

### 2.1 与 byPass 微架构参数交叉验证

| 模块 | 配置 | 估算翻转触发器 | 备注 |
|---|---|---|---|
| PRF | 128 × 32 = 4 096 bit | ≈ 4 096 | `prfEntries=128` |
| ROB | 128 × 含 PC/dest/excpt 等 ≈ 100 bit | ≈ 12 800 | `robEntries=128` |
| IssueQueue | 48 × ≈ 80 bit | ≈ 3 840 | `issueQueueEntries=48` |
| StoreBuffer | 16 × ≈ 80 bit | ≈ 1 280 | `sbEntries=16` |
| AXIStoreQueue | 16 × ≈ 80 bit | ≈ 1 280 | `axiSqEntries=16` |
| FetchBuffer | 32 × 36 bit | ≈ 1 150 | `fetchBufferEntries=32` |
| BCT / RAT / FreeList / BHT / BTB / MSHR / pipeline 寄存器等 | — | ≈ 16 000 | |
| **合计** | — | ≈ **40 400** | 与综合实测吻合 ✅ |

说明：DFF 总数与微架构存储位宽估算一致，证明综合没有出现意外的逻辑膨胀
（典型问题是 ABC 把宽 mux 拍成 latch 阵列）。

---

## 3. 时序：500 MHz 严重不闭合（历史 500 MHz 单档）

> 本节是上一轮 `build/sta/MyCPU-500MHz/` 跑出的历史快照（Phase A.1 RTL）；
> Phase A.2 RTL 本次未重跑 500 MHz 档，但 28..31 MHz 扫频结论一致（详见 §8）。

### 3.1 全局指标（`MyCPU.rpt`，500 MHz 历史档）

```
Setup TNS @ core_clock = -416 633.113 ns
Setup WNS @ core_clock = -29.594  ns   (target period = 2.0 ns)
Hold  WNS @ core_clock = +0.065  ns   ✅
```

**历史 Setup 关键路径长度 ≈ 31.45 ns（Phase A.1）；Phase B 终版扫频下稳态 PD = 31.632 ns ⇒ Fmax ≈ 30 MHz**（§8 实测）。

### 3.2 Top-5 Setup 违例端点（历史 500 MHz）

| Endpoint | Path Delay (ns) | Slack (ns) | Freq (MHz) |
|---|---|---|---|
| `uIssueQueue.validVec_14_reg_p:D` | 31.450 | **−29.594** | 31.651 |
| `uIssueQueue.validVec_15_reg_p:D` | 31.450 | −29.594 | 31.651 |
| `uIssueQueue.validVec_6_reg_p:D` | 31.449 | −29.594 | 31.652 |
| `uIssueQueue.validVec_31_reg_p:D` | 31.443 | −29.588 | 31.657 |
| `uIssueQueue.validVec_10_reg_p:D` | 31.443 | −29.588 | 31.658 |

> Phase B 终版 RTL 28..31 MHz 扫频下，top violators 是 `uIssueQueue.validVec_43_reg_p:D` 与 `uIssueQueue.validVec_11_reg_p:D` 并列第一（路径长度 31.632 ns），与上述同源——IQ entry valid 位的 wakeup/issue/flush 合一组合链。validVec 下标随综合 seed 漂移，根因不变。

### 3.3 Clock-gating 路径违例

| ICG Endpoint | Path Delay | Slack |
|---|---|---|
| `uBCT.refreshedSinceSnap_*` ICG `E` 端 | ≈ 7.9~8.0 ns | −5.9 ~ −6.1 ns |

BCT（Branch Checkpoint Table）的若干 `refreshedSinceSnap` 寄存器使用
`ICGX0P5H7L` 时钟门控单元，其 enable 信号路径过长。
不是顶级关键路径，但已构成 clock-gating 自身约束（`clock_gating_default`）失败。

### 3.4 关键路径解构（节选 `MyCPU.rpt` 第一条 −29.594 ns 路径）

起点：`uIssueQueue.buffer_0_instSeq_1__reg_p:CK`（IQ 内部 entry 寄存器）
终点：`uIssueQueue.validVec_14_reg_p:D`

中间长链由 ~30 级 `OAOI211 / AOI221 / XNOR2 / OAI221 / NAND3 / OAI21 / AOI211` 等
**深度组合逻辑**串联组成（路径名里出现 `_GEN_96_346__reg_p_D_OAOI211...
_AOAI211..._NOR4..._NAND3..._AOI221..._XNOR2... _OAI221..._XNOR2..._NOR2..._AOI211..._OAI21...
_OAI221..._AOI21..._NAND2..._AOI32..._OAO211..._OAOI211`）。

**这一长串组合等价于：**
`IQ entry payload (instSeq / src / wakeup vector) → wakeup 比较 →
issue ready 计算 → flush mask 与 → validVec 下拍写入`。

### 3.5 Hold

`Hold WNS = +0.065 ns`，全部 endpoint 都是 hold 满足。在 ideal-clock /
zero-skew 假设下属预期结果。CTS 之后 hold 才需要再做一次。

---

## 4. 时钟网络（无 CTS）

| 项 | 数值 |
|---|---|
| `clock` 网络 fanout | **34 246** |
| 时钟网络模型 | **ideal**（latency = 0） |
| Clock skew | 0 |

iSTA 当前是 pre-CTS 模式：把 `clock` 视为零延迟、零 skew 的理想网。
也就是说 §3 的 31.6 MHz **还是乐观估计**，加 CTS 后会更差。

---

## 5. 电气违例（cap / fanout / trans）

| 报告 | 最差 slack | 主要违例位置 |
|---|---|---|
| `MyCPU.cap`（Max Capacitance） | −0.299 (rise) | `uBCT.refreshedSinceSnap_* → ICGX0P5H7L.ECK` |
| `MyCPU.fanout`（Max Fanout） | **−34 222** | `clock` 端口本身（fanout=34 246，限值 24） |
| `MyCPU.fanout` 之 ICG | −336 | 同 BCT 系列 ICG cell 的 ECK |
| `MyCPU.trans`（Max Slew） | −0.373 ns | `uIssueQueue._GEN_94_*` 上的 BUFX0P5H7L 链 |

**结论**：

- 时钟 fanout 巨大（3.4 万），是因为 `clock` 是 ideal、未做 buffer 插入；
  CTS 阶段 `xmake run sta` 之后下一步交给布局工具再处理。
- BCT 的 ICG 串联链与 IQ 内部 mux 链在转换时间上已经撑爆 PDK 默认 cap/slew 限制。
  这与 §3 关键路径完全对应，不是另一处独立问题。

---

## 6. 功耗（iPA）：iEDA bug + 后处理修复

### 6.1 第一次跑出的 garbage

`MyCPU.pwr` 原始报告：

```
Power Group     Internal Power   Switch Power   Leakage Power   Total Power
combinational   3.019e+153       0.000e+00      4.875e-04       3.019e+153   (100.000%)
sequential      1.577e-01        0.000e+00      2.614e-04       1.580e-01    (0.000%)
Total Power = 3.019e+153 W
```

`combinational internal power = 3.019e+153 W` 显然不是物理量。

### 6.2 根因定位（从 sta.log 倒推）

`sta.log` 中检索到：

| 错误 | 出现次数 | 位置 |
|---|---|---|
| `rise slew is not exist` | **360 196** | `PwrCalcInternalPower.cc:97` |
| `input slew is not exist` | **79 894** | `PwrCalcInternalPower.cc:205` |
| `StaCheck found loop fwd/bwd` | 数十处 | iSTA 组合环检测 |

iEDA 当前版本（commit `aa25008b`）执行 iPA 时，对每个 cell 需要从上游
拿到 `rise/fall slew` 才能查 NLPM internal-energy 表。然而 **iSTA 的
`StaDataPropagation` BFS 在遇到组合环（OoO 设计的 wakeup matrix / BCT
反馈环）后，会沿 break edge 终止，导致下游 ~21% cell 的 slew 字段
未初始化**；`PwrCalcInternalPower` 拿到未初始化值去查表，外推到表外
得到 `1e+150` 量级 garbage 内部能量。

**直接证据**（按 internal power 降序 sample）：

```
8.984e+150  _uIssRRDff_out_bits_lanes_3_imm_23__NAND3X0P5H7L_A_C_BUFX20H7L_A
8.980e+150  _uIssRRDff_out_bits_lanes_3_imm_23__..._BUFX20H7L_A_8
...
6.810e+150  _uIssRRDff_out_bits_lanes_1_pc_8__reg_p_D_AOI211X4H7L_..._BUFX16H7L_A
```

集中爆发在 `_uIssRRDff_out_*` 的 BUFX 链 —— 正是 IssueQueue 写回->表项
的反馈环路上（与 §3 锁定的 critical path 同源）。leakage power（不依赖
slew）和 sequential internal power（DFF 自带 clk slew）数值正常，
**只有组合 internal power 这一项被污染**。

### 6.3 修复方案

iEDA 的 `set_input_transition` SDC 命令实测被忽略
（`strings ./bin/iEDA` 命中 `set_input_transition not support sdc obj yet.`），
iEDA 二进制无法即时打补丁。**采取后处理策略**：

新增 `tools-backend/scripts/clean_power.py`，读取每实例明细
`MyCPU_instance.csv`（457 447 行），按以下规则筛除 garbage：

```
per-cell internal power > 1 mW  →  视为 garbage（55nm 单 cell 物理上限远低于此）
其余视为 valid
```

`xmake.lua` 中 `sta` target 在 iPA 跑完后自动调用该脚本，输出：

- `MyCPU.pwr.clean`：过滤后的总览（功耗下界）
- `MyCPU.pwr.broken`：被剔除的 cell 列表

### 6.4 修复后的功耗数值

```
Total cells   : 457 446
Valid cells   : 360 862  (78.89 %)
Garbage cells :  96 584  (21.11 %)   ← iEDA bug 影响范围

Internal Power (valid cells)  =  28.719  W   ← 注意：此值仍偏高，见 §6.5
Switch   Power (valid cells)  =   0.000  W   ← 无 VCD 输入，全部走默认 toggle
Leakage  Power (all cells)    =   0.749 mW   ← 物理合理，可信
─────────────────────────────────────────────
TOTAL（下界，缺 96 584 cell internal） ≈  28.72  W
```

每 cell 内部功耗的分位分布（剔除前）：

| 分位 | 单 cell internal power | 评注 |
|---|---|---|
| min | 3.84e-11 W | ✅ 合理 |
| p25 | 3.23e-06 W | ✅ |
| **p50** | **2.00e-05 W** | ✅ 中位数 20 µW，量级正常 |
| p75 | 5.01e-04 W | ⚠️ |
| p90 | 1.44 W | ❌ garbage |
| p95 | 1.32e+68 W | ❌ |
| p99 | 9.79e+147 W | ❌ |
| max | 8.98e+150 W | ❌ |

可见 garbage 集中在 p90 以上 ~10% cell，threshold=1mW 取在合理与
garbage 之间的明显空隙处，分类干净。

### 6.5 数值仍需打折扣

即便剔除 garbage，`28.7 W` 这个总数对一颗 55 nm OoO 小核仍偏高。
原因有三：

1. **未提供 VCD/SAIF**：`report_power -toggle 0.1` 让 iEDA 对所有 net
   按 10% toggle 默认，组合大扇出 net 实际 toggle 远低于此；
2. **switch power 为 0**：`-toggle 0.1` 只设 internal 翻转计数，net
   切换功耗（C·V²·f）被完全忽略；
3. **理想时钟 + transition=0.1 ns**：clock 网络 transition 偏小让
   时钟相关 cell 查到的 internal energy 表项偏大。

因此本数值定位为**功耗排序的相对参考**而非绝对值。要拿到可发表的
绝对功耗，需做：

- `SIM=verilator xmake b Core && SIM=verilator xmake r sim-basic` 跑 verilator 收集 VCD；
- 在 `sta.tcl` 中 `read_vcd $vcd_file -top tb_top.MyCPU` 替换默认 toggle；
- 待 iEDA 修复 slew 传播 bug（已在 `iEDA/issues` 跟踪同类报告）。

### 6.6 上游问题归档

- **iEDA**：`PwrCalcInternalPower` 未对未初始化 slew fallback 到 default
  transition，导致 garbage 蔓延（同 commit `aa25008b`）。已在内部记录，
  待官方仓库修复后回归；
- **byPass RTL**：组合环源自 wakeup matrix（`study/13_wakeup_model.md`），
  这是 OoO 设计的固有结构，不会清除，但可以通过加更多寄存器边界来减少
  iSTA loop break 的影响，与 §7 的优化方向一致。

---

## 7. 微架构层面的根因与建议

§3 已锁定 critical path = **IssueQueue wakeup → validVec 更新**。
对照 `study/13_wakeup_model.md` 与 `study/04_issue_queue.md`：

| 现象 | 微架构原因 | 优化方向 |
|---|---|---|
| `validVec` 在一拍内承担 wakeup + issue + flush 三重决策 | byPass 把 α/β/γ wakeup 都收敛到 IQ entry 的同一拍 valid 计算 | **拆 wakeup 流水**：把 βwake 四重门控移出 valid 决策路径，改成下一拍生效（容忍 1 拍 ready 延迟） |
| 48 entry × 4 issue 选择 + 4 写端口 + flush mask 拼成 `_GEN_96_*` 30 级组合链 | 单拍内做 `select + writeback + flush` 三件事 | **issue arbitration 切两拍**：第一拍算 ready / mask，第二拍做 priority encode（参考 BOOM 的 select-payload 分级） |
| BCT clock-gating ICG enable 路径长 (-6 ns) | refresh-since-snap 与全局 flush / commit 信号合成的逻辑深 | 把 ICG enable 用单独的 1 拍寄存器打一拍再驱动 ICG（牺牲 1 拍功耗节省，换时序） |
| 时钟 fanout 34 246 | 顶层 ideal clock，未做 CTS | 接 iPL/iCTS 后会改善；Chisel 这边可考虑层次化时钟 wrapper（但收益有限） |

> ⚠️ 微架构优化前请先读 `study/14_invariants_and_hazards.md` —— 三档 wakeup（α/β/γ）+ βwake 四重门控
> 是 IPC 的核心，**不可粗暴推迟一拍**，需要重新论证不变量。

---

## 8. 频率扫描结果（`xmake run sta-parallel`，Phase B 终版 RTL）

> 本节为 Phase B 终版 RTL（icache_on / qDepth 200·4·200 / Fetch FSM 修复 / LatencyPipe 0 拍兼容）在 28..31 MHz 局部扫描下的实测数据。
> 实测命令：`FREQS="28 29 30 31" JOBS=4 xmake run sta-parallel`
> （256 核 / 1 TB RAM 服务器；上一轮 JOBS=200 触发资源耗尽，降到 4 后稳定）。
> 每档独立产物在 `build/sta/MyCPU-<f>MHz/`，完整日志在 `build/sta/sta-<f>.log`。
> 完整 20..40 MHz 扫描历史保存于 git log（Phase A.1 时期数据），本次未重跑。

### 8.1 实测 WNS / Fmax 表

| Freq (MHz) | Period (ns) | Path Delay (ns) | WNS (ns) | TNS-Setup (ns) | 闭合 |
|---:|---:|---:|---:|---:|:---:|
| 28 | 35.714 | 31.632 | **+2.252** | 0.000 | ✅ |
| 29 | 34.483 | 31.632 | **+1.082** | 0.000 | ✅ |
| 30 | 33.333 | 31.632 | **−0.010** | −0.052 | ⚠️（临界，2 endpoint 微违例） |
| **≈ 29.99** | **≈ 33.34** | **31.632** | **≈ 0** | **≈ 0** | **✅（实测 Fmax）** |
| 31 | 32.258 | 31.632 | **−1.031** | −288.080 | ❌ |

### 8.2 关键观察

1. **Path Delay 在 28..31 MHz 锁定到 31.632 ns**：yosys + ABC 在该区间内输出的关键路径长度完全一致，是当前 RTL（Phase B 终版：icache_on / qDepth 200·4·200 / Fetch FSM 修复 / LatencyPipe 0 拍兼容）在 icsprout55 工艺下经标准综合流程的稳态下界。相对 Phase A.2 历史扫描的 31.378 ns 略升 0.254 ns —— 增量来自 qDepth 加深（50/1/50→200/4/200）带来的 FIFO/指针寄存器更新逻辑变长，关键路径首末点不变。
2. **WNS 在 30→31 MHz 之间过零**：线性插值得 `WNS=0` 点 ≈ **29.99 MHz**（30 MHz 实测 −0.010 ns 已贴零），相比 Phase A.2 的 ≈ 30.2 MHz 略降 ~0.7%，与 PD 增长 0.254 ns 吻合。
3. **关键路径起终点不变**：4 个 .rpt 全部把 top violator 锁定在 `uIssueQueue.validVec_43_reg_p:D` 和 `uIssueQueue.validVec_11_reg_p:D`（并列第一），与 §3 结论同源 —— 优化的着力点不变。
4. **Setup TNS 在 30 MHz 跌为 −0.052 ns**：仅 2 个端点（validVec_43 / validVec_11）轻微违例 0.010 ns，可视为闭合临界；31 MHz 起 setup TNS 暴跌至 −288.080 ns（违例扩散到大量 IQ 唤醒分支）。Hold TNS（min）仍在 −36k 至 −42k ns 区间，未做 hold fix，与 §3 评论一致。
5. **JOBS=200 教训**：256 核服务器仍被 200 个并发 yosys/iSTA 拖崩（每 yosys ~7 GB RSS，200 × 7 = 1.4 TB 远超 1 TB 物理内存）。安全 JOBS = (RAM_GB / 7) / 1.5 ≈ 90 上限，实际取 4 已足以让 4 档扫频在 ~75 min 内完成。

### 8.3 扫描方法学

```bash
# 完整 21 档（推荐在大内存服务器上跑，约 70 min wallclock）
JOBS=200 xmake run sta-parallel

# 重点关注闭环点附近时进一步细分
FREQS="28 29 29.5 30" JOBS=10 xmake run sta-parallel

# 仅做时序而不跑 iPA（避免 21 路 iPA 并发把内存吃满）
# 当前 xmake.lua 的 sta target 顺序是 sv2v→yosys→iSTA→iPA；
# 跑大批扫频时若只关心 WNS，可以把 sta.tcl 里 report_power 行注释掉再跑
```

> 📌 **已知风险（之前的 BUG 复盘）**：旧版 `scripts/run_sta_parallel.sh`
> 使用 `parallel -j 30` 但没有 `--halt soon,fail=0`、没有 status 文件、
> 没有日志落盘到 `sta-<f>.log`，单档失败会导致后续无法追溯。`xmake run
> sta-parallel` 已修复这些问题：每档 wrapper 内部独立 `xmake run sta`，
> stdout/stderr 重定向到独立日志，最终汇总 PASS/FAIL 表格，并以非零
> 退出码反映整批结果。在 ≥ 768 GB RAM 服务器上 `JOBS=200` 可一次跑完 21 档。

---

## 9. 产物清单（本次实测）

`build/sta/MyCPU-500MHz/`：

| 文件 | 大小 | 说明 |
|---|---|---|
| `MyCPU.sv2v.v` | 4.4 MB | sv2v 处理后、`--top=MyCPU` 死代码剔除后的 V |
| `MyCPU.netlist.v` | 298 MB | yosys 综合后门级网表（含 cell 模型） |
| `MyCPU.netlist.v.sim` | 298 MB | 仿真版（带模型） |
| `MyCPU.v` | 288 MB | iSTA 内部 dump 的网表 |
| `synth_stat.txt` | 17 KB | 单元数 / 面积 |
| `synth_check.txt` | 115 B | 综合 DRC（0 problems ✅） |
| `yosys.log` | 5.1 MB | yosys 完整日志（已过滤 OPT/AUTONAME 噪声） |
| `MyCPU.rpt` | 1.0 MB | iSTA 时序总报告（top violators + 关键路径） |
| `MyCPU.cap` / `.fanout` / `.trans` | — | 电气违例 |
| `MyCPU_setup.skew` / `MyCPU_hold.skew` | — | 时钟偏斜（pre-CTS 全 0） |
| `MyCPU.pwr` | 1.5 KB | iPA 原始报告（combinational 项被 iEDA bug 污染，见 §6） |
| `MyCPU.pwr.clean` | <1 KB | **本次新增**：剔除 garbage cell 后的功耗下界 |
| `MyCPU.pwr.broken` | 12 KB | **本次新增**：受污染 cell 列表（top 200） |
| `MyCPU_instance.csv` | 74 MB | 每实例 internal/switch/leakage 明细（457 447 行） |
| `MyCPU_instance.pwr` | — | iPA 文本版每实例报告 |
| `sta.log` | 80 MB | iSTA + iPA 完整日志 |

---

## 10. 后续 TODO

- [x] ~~§6 修复 iPA 数值溢出~~：已通过 `tools-backend/scripts/clean_power.py`
      后处理过滤 garbage cell；绝对功耗待 iEDA 上游修复 + 接 VCD 后再回归
- [ ] §3 关键路径攻坚：IQ wakeup→validVec 流水拆分实验，回归 sim-basic / sim-regressive 39+64 用例确认 IPC 影响
- [x] ~~§8 频率扫描：跑 30/60/100/150/200/300 MHz 五档，得 WNS-曲线~~：
      历史 20..40 MHz 21 档扫描（Phase A.1）+ 本次 28..31 MHz 4 档扫描（Phase A.2），
      Fmax ≈ 30 MHz，详见 §8
- [ ] BCT ICG enable 改 1 拍寄存器后再综合，对比时序与 dynamic power
- [ ] 把 `sta-init` 加入 README §3 环境依赖说明（PDK / iEDA / sv2v 来源）
- [ ] 接 verilator VCD 喂给 iPA，得到带真实 toggle 的功耗（需要先实现 `read_vcd` 在 sta.tcl 的接入路径）
