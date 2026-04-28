# 14 · 关键不变量与多发射 Hazard 规则

> **角色**：本目录所有模块文档中提到的"关键不变量"统一汇总；改动任何 RTL 前必读。任意一项被破坏即可能引发回归。

## 1. 不变量清单（按里程碑顺序）

### v14 —— BCT 同拍 busyVen 旁路
- BCT ReadyTable 快照必须**包含**同拍 rename 新分配 pdst 的 busy 状态；
- 涉及：`device/ReadyTable.scala`、`device/BranchCheckpointTable.scala`；
- 破坏后果：mispredict 恢复后旧 pdst ready 错误保留，下游读 stale。

### v15 —— FetchBuffer ≥3 预测分支截断
- 当某槽预测 taken 时，本拍该槽之后 ≥3 个槽位 valid=0；
- 涉及：`dataLane/FetchBuffer.scala` / `Fetch.scala`；
- 破坏后果：错误路径污染 ROB → mispredict 后 squash 不彻底。

### TD-D —— mshrComplete Vec(2) per-slot 独立 ack
- `mshrComplete` IO 必须是 `Vec(2, Decoupled(...))`，每 slot 独立握手；
- MyCPU 顶层做"双 j 仲裁"（j=0 先选，j=1 后选）；
- 破坏后果：双 capture 时 mshrComplete drop，Load 永远不返回。

### v18 —— difftest 三件套
- `difftest/src/emu.lua` dmem 启动从 .bin 预载；
- `difftest/xmake.lua` 注入 `mem_65536x32.sv $readmemh dram.hex` 生成；
- `difftest/src/main.lua:268-274` mismatch 用 `or` 累加；
- 破坏后果：DRAM 初值错 / mismatch 漏报 / IROM 取错指令。

### TD-E (v19.3) —— Memory 双 capture 三大不变量
- **I-A bundle 原子性**：`in.ready=false` 时严禁 fresh MSHR alloc / fresh AR fire / mshrInFlightFifo enq；
- **I-B 双 capture all-or-none**：`numNeedDram=2 && freeCnt<2` 必整拍 stall；
- **I-C βwake 三重门控不可拆**：`RRExDff.out.fire && Memory.in.ready && !anyLoadInExBundle`。

### v20 —— βwake 范围
- **I-D**：βwake 启用 lane = lane0 + lane2 + lane3；lane1 仍禁用（lane1 βwake 未独立验证，留 P1）。

### v21 —— 代码清晰度（非时序，仅注释/结构）
- 文档与代码表述一致：CPUConfig 注释位宽 / IssueQueue 死注释清理；
- 不影响 RTL 行为；IPC 与 v20 baseline diff 0。

## 2. 多发射 Hazard 规则速查

| 规则 | 触发条件 | 实现位置 | 备注 |
|---|---|---|---|
| `pairRaw` | lane j (j<k) 是 Load 且 lane k 读 j.pdst | `Issue.scala:100-116` | Load N+1 才出结果，1 拍 RAW 不可同 bundle 解 |
| `doubleBrStall` | lane j (j<k) 与 lane k 都是 Branch/JALR | `Issue.scala:118-141` | Memory.redirect 单端口；TD-F 解除 |
| `doubleLoadStall` | （v19 已删除）| - | Memory 已支持双 capture |
| Memory bundle 原子 | `in.ready=0` 时禁所有 fresh 行为 | `Memory.scala:478-486, 671-744` | 断言守护 |
| Store commit 仅 lane0 | lane0 非 head 或非 Store 时整拍不提交 Store | `ROB.scala:69-78`, `MyCPU.scala:589-600` | 简化 SB→AXISQ 路径 |
| Branch 仅 lane0 验证 | lane0 计算 actual target | `Execute.scala:38-52` | TD-C 后 lane1 也可执行 Branch，但仍受 doubleBrStall 单仲裁约束 |

## 3. lane 能力规则（`LaneCapability.scala:84-92`）

```
lane0 / lane1：Full（ALU + Load + Store + Branch + JALR）
lane2 / lane3：ALU-only（iType / rType / uType）

loadLanes  = Seq(0, 1)
storeLanes = Seq(0, 1)   // 但 IQ 仲裁强制 Store 仅出 lane0（简化 commit）
branchLanes = Seq(0, 1)
```

## 4. 改 RTL 前的自检 checklist

修改任何 Memory / Issue / Refresh / IQ / BCT 文件前，逐项自检：

- [ ] v14 BCT busyVen 旁路是否被破坏？
- [ ] v15 FetchBuffer ≥3 截断是否还在？
- [ ] TD-D mshrComplete Vec(2) per-slot ack 是否还在？
- [ ] I-A Memory bundle 原子性（`in.ready=0` 阻断 fresh 分配）是否还在？
- [ ] I-B 双 capture all-or-none 是否被破坏？
- [ ] I-C βwake 三重门控是否完整？
- [ ] I-D βwake lane 范围（仅 0/2/3）是否被改？
- [ ] difftest 三件套（v18）是否被改？

任何一项答"不确定"，先用 rubber-duck 评审再动手。

## 5. 全量回归命令

```bash
cd /home/litian/Documents/stageFiles/studyplace/difftest

SIM=verilator xmake b rtl
SIM=verilator xmake b Core
SIM=verilator xmake r sim-basic        # 39 例
SIM=verilator xmake r sim-regressive   # 70 例
```

任意 FAIL 必须立刻定位是哪条不变量被破坏。
