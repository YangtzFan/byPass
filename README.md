# An Out-of-Order RISC-V CPU (Real OoO 2-issue, Lane-ified Backend)

## 1. 项目概述

- **微架构**：10 级流水 + 4 路乱序发射；前端 4-wide Fetch / Decode / Rename / Dispatch；后端单端口 bundle，bundle 内 4 lane 并行；
- **lane 能力**：lane0 / lane1 = Full（ALU + Load + Store + Branch + JALR）；lane2 / lane3 = ALU-only；
- **关键特性**：BPU（BHT64 + BTB32）/ 48 项 IssueQueue / 128 项 ROB / 16 项 StoreBuffer / 双 capture MSHR / 三档 wakeup（α / β / γ）+ βwake 三重门控；
- **回归状态**：sim-basic 39/39 PASS、sim-regressive 70/70 PASS；
- **IPC 峰值**：`kernels_dep_chain_ooo4_unroll = 2.063`。

## 2. 关键参数（`src/main/scala/CPUConfig.scala`）

| 参数 | 值 | 备注 |
|---|---|---|
| `issueWidth` / `commitWidth` / `renameWidth` / `fetchWidth` | 4 | bundle 宽度 |
| `issueQueueEntries` | 48 | 实测最优 |
| `robEntries` | 128 | |
| `prfEntries` | 128 | p0 硬连 0 |
| `prfReadPorts` / `prfWritePorts` | 8 / 4 | |
| `sbEntries` | 16 | StoreBuffer |
| `axiSqEntries` | 16 | AXIStoreQueue |
| `fetchBufferEntries` | 32 | |
| `bhtEntries` | 64 | PC[7:2] 索引 |
| `btbEntries` | 32 | |
| `maxBranchCheckpoints` | 8 | |

## 3. 环境依赖

- Java 11+
- Mill
- xmake
- Verilator
- [Verilua]()

## 4. 快速开始

```bash
# 1. 初始化子模块
xmake run init

# 2. 编译 Scala / Chisel
xmake run comp

# 3. 生成 RTL（输出到 build/rtl）
xmake run rtl
```

后端流程基于 `tools-backend/`（内置 yosys-sta 脚本）+ `iEDA` + `sv2v` + `icsprout55` PDK，
顶层入口在根目录 `xmake.lua`，提供 4 个 target：

```bash
# 一次性环境准备：下载预编译 iEDA + sv2v + 克隆 icsprout55 PDK
xmake run sta-init

# 仅跑 yosys 综合（sv2v + yosys → 门级网表 + 面积报告）
xmake run sta-syn

# 完整后端：sv2v + yosys 综合 + iSTA 时序 + iPA 功耗
xmake run sta

# 清理 build/sta 与 tools-backend/result
xmake run sta-clean
```

可选环境变量（均有默认值）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DESIGN` | `MyCPU` | 顶层模块名 |
| `PDK` | `icsprout55` | 工艺库 |
| `CLK_PORT_NAME` | `clock` | 时钟端口（byPass 顶层为 `clock`） |
| `CLK_FREQ_MHZ` | `500` | 目标频率，用于 SDC `create_clock` |
| `SDC_FILE` | `tools-backend/scripts/mycpu.sdc` | 约束文件 |
| `RTL_FILES` | `build/rtl/*.sv` | 综合输入 RTL 列表 |
| `O` | `build/sta` | 结果输出根目录 |

报告位于 `build/sta/<DESIGN>-<CLK_FREQ_MHZ>MHz/`，关键产物：

- `MyCPU.netlist.v` / `synth_stat.txt` / `synth_check.txt`：综合网表 + 面积 + DRC
- `MyCPU.rpt`：iSTA 时序总报告（WNS / TNS / 关键路径）
- `MyCPU.cap` / `.fanout` / `.trans`：电容 / 扇出 / 转换违例
- `MyCPU_setup.skew` / `MyCPU_hold.skew`：时钟偏斜
- `MyCPU.pwr`：iPA 功耗（dynamic / leakage / 总功耗）

详细解读见 `study/15_yosys_sta_backend.md` 与 `study/16_backend_analysis_basics.md`。

## 5. 验证框架

详见仓库[difftest](https://github.com/YangtzFan/difftest)
```bash
cd ../difftest
xmake b rtl            # 生成并后处理 SV
xmake b Core           # verilator 编译
xmake r sim-basic      # 39 例
xmake r sim-regressive # 70 例
```

## 6. 目录结构

```
byPass/
├── src/main/scala/
│   ├── CPUConfig.scala            全局参数
│   ├── MyCPU.scala                顶层接线、wakeup 网络、冲刷路径
│   ├── dataLane/                  流水线各 stage（Fetch/Rename/Dispatch/IQ/Issue/RR/Execute/Memory/Refresh/...）
│   └── device/                    ROB / PRF / RAT / FreeList / SB / AXISQ / BCT / ReadyTable / BHT / BTB / DRAM / IROM
├── study/                         ⭐ 微架构研究文档（读懂源码必看）
├── TASK.md                        当前迭代任务（v21 已闭环）
└── build.sc / xmake.lua           构建脚本
```

## 7. 阅读建议

- **微架构原理**：从 `study/SYSTEM_TOP.md` 起步，按 14 个模块文档逐项读，每个文档都对应 Chisel 源码锚点；
- **关键不变量**：`study/14_invariants_and_hazards.md`（改 RTL 前必读）；

