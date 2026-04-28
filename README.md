# An Out-of-Order RISC-V CPU (OoO 4-issue, 2 full lanes)

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

## 6. 快速开始

```bash
# 1. 初始化子模块
xmake run init

# 2. 编译 Scala / Chisel
xmake run comp

# 3. 生成 RTL（输出到 build/rtl）
xmake run rtl
```

## 7. 验证框架

详见仓库[difftest](https://github.com/YangtzFan/difftest)
```bash
cd ../difftest
xmake b rtl            # 生成并后处理 SV
xmake b Core           # verilator 编译
xmake r sim-basic      # 39 例
xmake r sim-regressive # 70 例
```

## 8. 目录结构

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

## 9. 阅读建议

- **微架构原理**：从 `study/SYSTEM_TOP.md` 起步，按 14 个模块文档逐项读，每个文档都对应 Chisel 源码锚点；
- **关键不变量**：`study/14_invariants_and_hazards.md`（改 RTL 前必读）；

