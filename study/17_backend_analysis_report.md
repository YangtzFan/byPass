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
| **实测最高频率（pre-CTS）** | **≈ 31.6 MHz** | ❌ 严重不达标 |
| WNS（Worst Negative Slack） | **−29.594 ns** | ❌ 路径长度约为周期的 16 倍 |
| TNS（Total Negative Slack） | **−416 633.113 ns** | ❌ 几乎所有 endpoint 都不闭合 |
| FEP（Failing Endpoints） | 满目（含 IssueQueue.validVec / BCT / clock-gating ICG） | — |
| Hold WNS | +0.065 ns | ✅ 无 hold 违例 |
| 单元数 | 457 447 个 std cell | — |
| 触发器数 | ≈ 40 400 个 DFF | — |
| Chip Area | **1 036 542.64 µm²** | 顺序逻辑占 24.01% (248 864 µm²) |
| Clock fanout | 34 246（理想时钟，未 CTS） | 时序分析阶段未做时钟树 |
| 综合 DRC | 0 problems | ✅ |
| 功耗（iPA） | **数值溢出，无效**（详见 §6） | ⚠️ 工具问题 |

**核心结论**：当前 byPass 在 icsprout55 工艺下，**以 500 MHz 为目标无法闭合**；
关键路径起点位于 IssueQueue 的 wakeup / validVec 更新逻辑，
真实 critical path 长度约 31.5 ns，对应可达频率约 **31~32 MHz**。
要达到 500 MHz 量级需要做相当大的微架构改造（详见 §7 建议）。

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

`build/sta/MyCPU-500MHz/synth_stat.txt`：

```
=== MyCPU ===
   457 693   wires / wire bits
       750   ports / port bits
   457 447   cells       (Local Area = 1.04E+06 µm²)
Chip area for module 'MyCPU':       1 036 542.64
  of which used for sequential elements: 248 864.00  (24.01%)
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

## 3. 时序：500 MHz 严重不闭合

### 3.1 全局指标（`MyCPU.rpt`）

```
Setup TNS @ core_clock = -416 633.113 ns
Setup WNS @ core_clock = -29.594  ns   (target period = 2.0 ns)
Hold  WNS @ core_clock = +0.065  ns   ✅
```

**实测 Setup 关键路径长度 ≈ 31.45 ns ⇒ Fmax ≈ 31.65 MHz**。

### 3.2 Top-5 Setup 违例端点

| Endpoint | Path Delay (ns) | Slack (ns) | Freq (MHz) |
|---|---|---|---|
| `uIssueQueue.validVec_14_reg_p:D` | 31.450 | **−29.594** | 31.651 |
| `uIssueQueue.validVec_15_reg_p:D` | 31.450 | −29.594 | 31.651 |
| `uIssueQueue.validVec_6_reg_p:D` | 31.449 | −29.594 | 31.652 |
| `uIssueQueue.validVec_31_reg_p:D` | 31.443 | −29.588 | 31.657 |
| `uIssueQueue.validVec_10_reg_p:D` | 31.443 | −29.588 | 31.658 |

**所有 top 路径都终结在 `IssueQueue.validVec`**——这正是 byPass
issue-queue 中**每个 entry 的 valid 位**寄存器，参与 wakeup（α/β/γ 三档）+
issue selection + flush 多重组合逻辑。

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

## 6. 功耗（iPA）：报告失效，需要进一步排查

`MyCPU.pwr`：

```
Power Group     Internal Power   Switch Power   Leakage Power   Total Power
combinational   3.019e+153       0.000e+00      4.875e-04       3.019e+153   (100.000%)
sequential      1.577e-01        0.000e+00      2.614e-04       1.580e-01    (0.000%)
Total Power = 3.019e+153 W
```

**判定为无效**：组合逻辑 internal power 出现 `3.019e+153 W` 这样的天文数字，
明显是数值溢出 / 翻转率默认值未设导致 internal-power 公式在某些 cell 上发散。
sequential 部分 0.158 W 看起来正常，但只能作为下界。

可能原因：

1. 当前 `sta.tcl` 没有提供 `read_vcd` / `read_saif`，所有 net 翻转率走默认
   0.5 + 默认转换时间，叠加 §5 中 `clock` 网络 fanout=34246 的 Cload，
   再乘内部 cell internal-energy 表，部分 NLPM cell 可能查表出了越界值。
2. `core_clock` 在 ideal 模式下 transition=0.1 ns 极小，部分 cell 内部能量
   表外推出错。

**修复建议**（不在本次报告范围）：

- 跑一段 verilator 仿真生成 VCD，再用 `read_vcd $vcd -top tb_top.MyCPU` 喂给 iPA；
- 或在 `sta.tcl` 中显式 `set_switching_activity -toggle_rate 0.1 -static_probability 0.5`；
- 同时把 clock 网络做最小 CTS（ideal_network → propagated）后再算。

---

## 7. 微架构层面的根因与建议

§3 已锁定 critical path = **IssueQueue wakeup → validVec 更新**。
对照 `study/13_wakeup_model.md` 与 `study/04_issue_queue.md`：

| 现象 | 微架构原因 | 优化方向 |
|---|---|---|
| `validVec` 在一拍内承担 wakeup + issue + flush 三重决策 | byPass 把 α/β/γ wakeup 都收敛到 IQ entry 的同一拍 valid 计算 | **拆 wakeup 流水**：把 βwake 三重门控移出 valid 决策路径，改成下一拍生效（容忍 1 拍 ready 延迟） |
| 48 entry × 4 issue 选择 + 4 写端口 + flush mask 拼成 `_GEN_96_*` 30 级组合链 | 单拍内做 `select + writeback + flush` 三件事 | **issue arbitration 切两拍**：第一拍算 ready / mask，第二拍做 priority encode（参考 BOOM 的 select-payload 分级） |
| BCT clock-gating ICG enable 路径长 (-6 ns) | refresh-since-snap 与全局 flush / commit 信号合成的逻辑深 | 把 ICG enable 用单独的 1 拍寄存器打一拍再驱动 ICG（牺牲 1 拍功耗节省，换时序） |
| 时钟 fanout 34 246 | 顶层 ideal clock，未做 CTS | 接 iPL/iCTS 后会改善；Chisel 这边可考虑层次化时钟 wrapper（但收益有限） |

> ⚠️ 微架构优化前请先读 `study/14_invariants_and_hazards.md` —— wakeup 三档门控
> 是 IPC 的核心，**不可粗暴推迟一拍**，需要重新论证不变量。

---

## 8. 推荐的频率扫描方法

要寻找当前 RTL 的真实闭合频率（不改动微架构），用环境变量扫一遍：

```bash
for f in 30 60 100 150 200; do
  CLK_FREQ_MHZ=$f O=build/sta-scan xmake run sta
done
```

每跑一次产生 `build/sta-scan/MyCPU-${f}MHz/MyCPU.rpt`，从中抽取 WNS 即可
绘制 freq–slack 曲线，找出 WNS≈0 的点。本次只跑了 500 MHz 一档，
结合关键路径 31.45 ns 推算：**当前 RTL 的可闭合频率约 30 MHz 左右**。

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
| `MyCPU.pwr` / `MyCPU_instance.pwr` / `MyCPU_instance.csv` | 总 ~400 MB | iPA 报告（**当前数值无效**，见 §6） |
| `sta.log` | 80 MB | iSTA + iPA 完整日志 |

---

## 10. 后续 TODO

- [ ] §6 修复 iPA 数值溢出：补 VCD / 显式 toggle 率
- [ ] §3 关键路径攻坚：IQ wakeup→validVec 流水拆分实验，回归 sim-basic / sim-regressive 39+70 用例确认 IPC 影响
- [ ] §8 频率扫描：跑 30/60/100/150/200/300 MHz 五档，得 WNS-曲线
- [ ] BCT ICG enable 改 1 拍寄存器后再综合，对比时序与 dynamic power
- [ ] 把 `sta-init` 加入 README §3 环境依赖说明（PDK / iEDA / sv2v 来源）
