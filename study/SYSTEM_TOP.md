# 微架构总览（SYSTEM_TOP）

> 本文件是整机视角的"系统总览"。每个功能点（前端 / 重命名 / 派发 / IQ / Issue 仲裁 / ReadReg / Execute / Memory+MSHR / Refresh / ROB / BCT / 存储子系统 / 三档 wakeup / 不变量与 hazard）已**拆出独立模块文档**，位于本目录下，便于读者快速定位 Chisel 源码并理解原理。

## 模块文档导航（study/ 目录）

| 文件 | 内容 | 主源文件 |
|---|---|---|
| [`01_frontend.md`](01_frontend.md) | Fetch + BHT + BTB + FetchBuffer | `dataLane/Fetch.scala`、`device/BHT.scala`、`device/BTB.scala`、`dataLane/FetchBuffer.scala` |
| [`02_rename.md`](02_rename.md) | Rename + RAT + FreeList + ReadyTable | `dataLane/Rename.scala`、`device/RAT.scala`、`device/FreeList.scala`、`device/ReadyTable.scala` |
| [`03_dispatch.md`](03_dispatch.md) | Dispatch + ROB tail / SB 槽分配 + IQ 入队 | `dataLane/Dispatch.scala` |
| [`04_issue_queue.md`](04_issue_queue.md) | IssueQueue（48 项）+ wakeup 接收逻辑 | `dataLane/IssueQueue.scala` |
| [`05_issue_arbitration.md`](05_issue_arbitration.md) | Issue 多发射仲裁（`pairRaw` / `doubleBrStall`） | `dataLane/Issue.scala` |
| [`06_readreg_prf.md`](06_readreg_prf.md) | ReadReg + PRF（8 读 4 写）+ 同拍写优先 bypass | `dataLane/ReadReg.scala`、`device/PRF.scala` |
| [`07_execute.md`](07_execute.md) | Execute 双 ALU + EX-in-EX 旁路 + 分支验证 | `dataLane/Execute.scala` |
| [`08_memory_mshr.md`](08_memory_mshr.md) | Memory（非阻塞 Load + 双 capture MSHR + AR 仲裁） | `dataLane/Memory.scala` |
| [`09_refresh.md`](09_refresh.md) | Refresh + per-lane 写口仲裁 + ROB done | `dataLane/Refresh.scala`、`MyCPU.scala:419-515` |
| [`10_rob.md`](10_rob.md) | ROB（128 项）+ 4 lane dispatch / commit | `device/ROB.scala` |
| [`11_branch_checkpoint.md`](11_branch_checkpoint.md) | BCT + 重定向冲刷路径 | `device/BranchCheckpointTable.scala`、`MyCPU.scala:370-406` |
| [`12_storage_subsystem.md`](12_storage_subsystem.md) | StoreBuffer / AXIStoreQueue / DRAM / IROM | `dataLane/StoreBuffer.scala`、`memory/AXIStoreQueue.scala`、`memory/DRAM_AXIInterface.scala`、`memory/IROM.scala` |
| [`13_wakeup_model.md`](13_wakeup_model.md) | α / β / γ 三档 wakeup 时序 + βwake 三重门控 | `MyCPU.scala:545-572`、`IssueQueue.scala:153-186` |
| [`14_invariants_and_hazards.md`](14_invariants_and_hazards.md) | 不变量清单 + 多发射 hazard 规则 | 各模块综合 |

## 阅读建议

- **整机一眼图**：先看下文 §一（流水线全景图与 Dff 边界）；
- **针对某模块**：直接跳转上表对应 md；每个模块文档内含"源文件锚点 + 接口 + 数据通路 + 关键不变量"；
- **追历史变迁**：本文件不记录历史，请查 `../scripts/LOG.md`、`../scripts/BUG.md`、`../scripts/STUDY.md.v19_3bak`。

---

> 本文件聚焦"**当前是什么样**"，不记录历史演进。历史里程碑见 `../scripts/LOG.md`，BUG 复盘见 `../scripts/BUG.md`，里程碑路线图见 `../scripts/PLAN.md`，当前迭代任务见 `../TASK.md`。
>
> **基准时间点**：v20 IPC 回收闭环（lane2/3 βwake 启用 + 三重门控保留），v21 代码清理已完成。
> **回归状态**：sim-basic 39/39 PASS、sim-regressive 70/70 PASS（双绿）。
> **历史快照备份**：`../scripts/STUDY.md.v19_3bak`（含 v13~v19 全部历史章节，仅作归档）。

---

## 一、流水线全景图与 Dff 边界

**架构概览**：4 路并发取指 / 解码 / 重命名 / 派发 + 单端口 Issue / ReadReg / Execute / Memory / Refresh 主流水。lane0..lane3 是 bundle 内位置，决定能力差异（详见 `LaneCapability.scala:84-92`）。

```
Fetch(4W) → [FetchBuffer(32)] → Decode(4W) → [DecRenDff]
  → Rename(4W) → [RenDisDff] → Dispatch(4W) → [IssueQueue(48)]
  → Issue(1)   → [IssRRDff]  → ReadReg(1)   → [RRExDff]
  → Execute(1) → [ExMemDff]  → Memory(1)    → [MemRefDff]
  → Refresh(1) → Commit[ROB head]
```

- **6 个流水寄存器边界**：DecRenDff / RenDisDff / IssRRDff / RRExDff / ExMemDff / MemRefDff（皆 `BaseDff` 实例，`dataLane/BaseDff.scala`）。
- **lane 能力**（`dataLane/LaneCapability.scala:84-92`）：
  - lane0 / lane1：Full（ALU + Load + Store + Branch + JALR）；
  - lane2 / lane3：ALU-only（iType / rType / uType）；
  - `loadLanes = Seq(0, 1)`、`storeLanes = Seq(0, 1)`、`branchLanes = Seq(0, 1)`。
- **宽度配置**（`CPUConfig.scala`）：`issueWidth = commitWidth = renameWidth = fetchWidth = 4`；Issue/Execute/Memory 仍是 1 bundle/cycle，bundle 内 4 lane 并行。

---

## 二、前端子系统（Fetch → BHT/BTB → FetchBuffer）

**机制**：BHT64 + BTB32 预测驱动的 4 路对齐取指，FetchBuffer(32) 解耦 Fetch 与 Decode 节奏差。

- **Fetch**（`dataLane/Fetch.scala`）：每拍 IROM 读 128 bit（4×32）；`PC[3:2]` 决定 startSlot，对齐前槽位 valid=0；预测跳转后 ≥3 槽位截断 valid（v15 不变量，避免错误路径污染 ROB）。
- **BHT**（`device/BHT.scala`）：64 项，2-bit Gray 计数；PC[7:2] 索引；Execute 阶段更新（lane0-only 来源）。
- **BTB**（`device/BTB.scala`）：32 项，主要给 JALR 间接目标预测；PC 低位索引。
- **FetchBuffer**（`dataLane/FetchBuffer.scala`）：环形缓冲 32 项；每拍 enq ≤4、deq ≤4；redirect/flush 整体清空。

---

## 三、重命名与调度（Rename → Dispatch → IssueQueue）

**机制**：32 个逻辑寄存器（x0~x31）→ 128 个物理寄存器（p0~p127，p0 硬连 0），同拍分配 ROB / SB 指针、抓 Branch checkpoint。

- **Rename**（`dataLane/Rename.scala` + `device/RAT.scala` + `device/FreeList.scala` + `device/ReadyTable.scala`）：
  - RAT 4 lane 并发查询 / 更新；同拍更老 lane newPdst 旁路给较新 lane（rename bypass）；
  - FreeList：`allocOffsets(k)` 累加前 k 条需分配数；不足整 bundle stall；
  - ReadyTable：新 pdst 标 busy；同拍 Refresh 写 PRF 同步置 ready（v14 BCT 快照同拍 busyVen 旁路）；
  - Branch checkpoint：B/JALR 触发，每拍最多保 2 个；保留 RAT 全表 + FreeList.head + ReadyTable 128-bit。
- **Dispatch**（`dataLane/Dispatch.scala`）：分配 ROB tail（4 个连续）；Store 占 SB 槽（含 storeSeqSnap）；指令写入 IQ 空闲槽，同拍查 ReadyTable 初始化 src{1,2}Ready 并叠加同拍 wakeup 旁路（IssueQueue.scala:153-167）。
- **IssueQueue**（`dataLane/IssueQueue.scala`）：48 项；`NumWakeupSrcs = refreshWidth + memoryWidth + executeWidth = 12`；src{1,2}Ready 是 Reg，wakeup 在下一拍翻转（line 175-186）；oldest-ready 选择采用 `instSeq` 循环序号比较。

---

## 四、执行后端（Issue → ReadReg → Execute → Memory → Refresh）

### 4.1 Issue —— 多发射仲裁（`dataLane/Issue.scala`）

- 4 lane 并发，约束：
  - **pairRaw**（line 100-116）：lane j (j<k) 是 Load 且 lane k 读 j.pdst → lane k stall（Load N+1 才出结果，1 拍 RAW 不可同 bundle 解）；
  - **doubleBrStall**（line 118-141）：lane j (j<k) 是 Branch/JALR 且 lane k 也是 → lane k stall（Memory.redirect 单端口）；
  - **doubleLoadStall 已删除**（v19 TD-E）：Memory 已支持双 capture / per-lane mshrCaptureFire / AR pending 仲裁。
- `finalIssue(k) := laneCanIssue(k) && !pairRaw(k) && !doubleBrStall`，至少 1 lane 发射时整 bundle out.valid=1。

### 4.2 ReadReg —— PRF 读 + 同拍写优先 bypass（`dataLane/ReadReg.scala` + `device/PRF.scala`）

- 8 读口（= `2 × issueWidth`）/ 4 写口（= `refreshWidth`）；
- 同拍 Refresh 写优先 bypass：读端检测 `wen[i] && waddr[i] === raddr` 时返回 wdata —— 这是 βwake N+3 时序得以工作的关键基础设施。

### 4.3 Execute —— 双 ALU + EX-in-EX 旁路（`dataLane/Execute.scala`）

- lane0/lane1 各有 ALU；lane2/lane3 ALU-only 也共享 ALU 实例；
- **EX-in-EX 同拍前递**（line 71-96）：lane0 ALU 结果同拍前递给 lane1 src1/src2（处理 lane0→lane1 RAW）；
- 分支验证：lane0 计算实际 target 与 BTB/BHT predicted 比较；mispredict 信号 `lane0MispredictEarly` 触发 redirect；
- BHT/BTB 更新口都在 Execute；Memory.redirect 有效时抑制（防错误路径污染）。

### 4.4 Memory —— 非阻塞 Load + 双 capture MSHR（`dataLane/Memory.scala`，755 行）

- **Store 路径**（line 200-215）：per-lane sType 直接写 StoreBuffer（字节级掩码 + 数据），Store 序号 storeSeqSnap 跟随；
- **Load 路径**（line 218-450）：
  1. SB 字节级转发 + olderUnknown（更老未知地址 store）检测；
  2. AXIStoreQueue committed-store 转发；
  3. 不全覆盖 → fresh AR 发起 DRAM 读（`sqLoadAddr.fire`）；
  4. DRAM 返回时由 `mshrComplete` Vec(2) per-slot 独立 ack 给 MyCPU，走专用 Refresh 写口写 PRF。
- **MSHR Vec(2)**：
  - `freeMask` + `PopCount(freeMask) >= numNeedDram`：all-or-none accept（line 429）；
  - `captureSlotForLane`：lane0 → `allocSlot`（最低空闲），lane1 → `allocSlot1`（次低空闲，仅 lane0 也 capture 时）；
  - AR 仲裁：`launchPendingAR > launchFreshAR`，fresh-oldest = `freshFireLane = PriorityEncoder(captureAcceptedVec)`（line 435-439）；
  - 其余 fresh capture 设 `mshrArPending=true`，下一拍 `launchPendingAR` 补发；
  - `mshrCaptureFire` Vec(loadLanes.size, Bool)：per-lane 真"capture accepted"，供 MyCPU 顶层抑制 γwake + 用作 outMaskBits 把 capture lane 输出位扣除（防 Refresh 把"地址当数据"写 PRF）；
  - `memStall` 三态：`anyOlderUnk`(整 stall) / `anyCaptureAccepted`(放行) / `numNeedDram=/=0 且 freeCnt<2`(整 stall)。
- **Load-Use Hazard 槽**（`MyCPU.scala:327-397`）：`hazard vec(8) = 3 × loadLanes.size + 2 × MSHR`，覆盖 Issue → RR → Ex → Mem → MSHR-pending → MSHR-result 全链路；
- **不变量断言**（line 671-754）：bundle 原子性、双 lane capture 必进不同槽、`mshrCaptureFireWire === anyCaptureAccepted`。

### 4.5 Refresh —— PRF 写 + per-lane 仲裁（`dataLane/Refresh.scala` + `MyCPU.scala:419-515`）

- **`effRef*` 仲裁**：把 mshrComplete Vec(2) 与正常 Refresh per-lane 合并：
  - `mshrLaneClaimByJ(j)(k)=1`：mshrComplete(j) 占 lane k；j=0 先选、j=1 后选；
  - 若 lane k 被 mshr 占走，正常 Refresh 那条移到下一可用 lane。
- 4 PRF 写口并发；ReadyTable readyVen/readyAddr 同步置 ready；ROB.refresh per-lane 标 done。

---

## 五、三档 Wakeup 时序模型（α / γ / β）

**目的**：在 PRF 写回前提前广播 pdst，让消费者 IQ 更早 ready 起步，缩短 Load-Use / RAW 距离。

| 档 | 广播位置 | 生产者写 PRF 拍 | 消费者读 PRF 拍 | 安全机制 |
|---|---|---|---|---|
| α | Refresh 同拍 | N | N+1 | 最保险（Refresh 在写）|
| γ | Memory 级 | N+1 | N+2 | per-lane mshrCaptureFire 抑制（Load 走 MSHR 那拍不广播 γ）|
| β | Execute 级 | N+3 | N+3（同拍写优先 bypass）| **三重门控**（v19 TD-E）|

**βwake 三重门控**（`MyCPU.scala:545-572`）：
```scala
val anyLoadInExBundle = (0 until executeWidth).map { k =>
  uRRExDff.out.bits.validMask(k) && uRRExDff.out.bits.lanes(k).type_decode_together(4)
}.reduce(_ || _)
val exAdvance = uRRExDff.out.fire && uMemory.in.ready && !anyLoadInExBundle
```
- 第 1 重 `RRExDff.out.fire`：确保生产者本拍真推进；
- 第 2 重 `Memory.in.ready`：确保当前 ExMemDff→Memory 通路无反压；
- 第 3 重 `!anyLoadInExBundle`：消除"同 bundle Load 触发 memStall 把 ALU 生产者一并卡在 ExMemDff"路径，从源头保证 N+3 抵达 Refresh。
- **βwake 范围（v20 P0+P1 安全版）**：lane0 + lane2 + lane3 启用；lane1 仍禁用（保留 v19.3 不变量 I-D，留待独立验证）。
  - lane2/3 是 ALU-only，本身永不可能是 Load；但仍受第 3 重门控保护，因 bundle 原子推进 → 同 bundle 任意 Load 触发 memStall 时 lane2/3 ALU 生产者也会被卡。

> ✅ **v20 收益**：lane2/3 βwake 释放后，对"bundle 不含 Load"的 ALU-heavy 路径有显著 IPC 提升（详见 §11.4）。原"βwake 第 3 重一刀切"导致 v19.3 IPC 回退已修复。

---

## 六、乱序保留与投机回滚

- **ROB**（`device/ROB.scala`）：128 项；4 lane 并发 dispatch（4 个连续 tail）；4 lane Refresh 标 done；提交按 head 顺序，Store 仅 lane0 可提交。
- **BCT (Branch Checkpoint Table)**（`device/BranchCheckpointTable.scala`）：8 项；保存 RAT 全表 + FreeList.head + ReadyTable 128-bit；`refreshedSinceSnap[idx]` 维护快照后已 ready 的 pdst 位向量，恢复时 OR 回快照防 stale。
- **重定向冲刷路径**（`MyCPU.scala:370-406`）：
  - `laneFlush` 函数对每个 Dff 逐 lane 比较 `robIdx > memRedirectRobIdx` 清 validMask；全 0 整 bundle 丢；
  - IQ flush=1 全部 valid=0；
  - FreeList.head / RAT 由 BCT.recoverIdx 恢复；
  - MSHR 清掉年轻于分支的条目（`mshrFlushed[i]` 标记）；
  - StoreBuffer 按 storeSeqSnap 精确回滚。

---

## 七、存储子系统

- **StoreBuffer**（`dataLane/StoreBuffer.scala`，深度 16）：投机 Store；字节级；commit 时把数据搬到 AXIStoreQueue。
- **AXIStoreQueue**（`memory/AXIStoreQueue.scala`，深度 16）：committed-store 等待 DRAM 写入；committed-store forward 接口供 Memory 转发；`loadInflightCnt` 配合 AXI outstanding=2。
- **DRAM 接口**（`memory/DRAM_AXIInterface.scala`）：AXI outstanding=2；`readReqFifo / readRespFifo` 各 2 项。
- **IROM**（`memory/IROM.scala`）：取指 ROM；通过 difftest xmake.lua `$readmemh` 注入 hex 内容。
- **Load 字节合并**（`Memory.scala mergeLoadSources` ~ line 130）：优先级 SB > AXIStoreQueue > DRAM；按 mask 字节级合并。

---

## 八、关键不变量（必守，破坏即回归）

1. **v14**：BCT ReadyTable 快照同拍 busyVen 旁路；
2. **v15**：FetchBuffer ≥3 预测分支截断；
3. **TD-D**：`mshrComplete` IO=Vec(2) per-slot 独立 ack；MyCPU 双 j 仲裁；
4. **v18**：difftest emu.lua dmem 启动从 .bin 预载 + xmake.lua 注入 `mem_65536x32.sv $readmemh dram.hex` + main.lua mismatch 用 `or` 累加；
5. **TD-E（v19.3）**：
   - **I-A** Memory bundle 原子性：`in.ready=false` 时禁止 fresh MSHR alloc / fresh AR fire / mshrInFlightFifo enq；
   - **I-B** 双 capture all-or-none：`numNeedDram=2 && freeCnt<2` 必整拍 stall；
   - **I-C** βwake 三重门控不可拆：任一缺失即破坏 N+3 PRF 写时序假设；
   - **I-D** βwake 范围（v20 已扩展）：lane0 + lane2 + lane3 启用，lane1 仍禁用（lane1 βwake 未独立验证，留 P1 后续）。

---

## 九、多发射 Hazard / 同 Bundle 互斥规则

| 规则 | 触发 | 实现位置 | 备注 |
|---|---|---|---|
| `pairRaw` | lane j (j<k) 是 Load 且 lane k 读 j.pdst | Issue.scala:100-116 | Load N+1 才出结果，1 拍 RAW 不可同 bundle 解 |
| `doubleBrStall` | lane j (j<k) 与 lane k 都是 Branch/JALR | Issue.scala:118-141 | Memory.redirect 单端口；TD-F 解除 |
| `doubleLoadStall` | （v19 已删除） | - | Memory 已支持双 capture |
| Memory bundle 原子 | in.ready=0 时禁所有 fresh 行为 | Memory.scala:478-486, 671-744 | 断言守护 |
| Store commit 仅 lane0 | lane0 非 head 或非 Store 时整拍不提交 Store | ROB.scala:69-78, MyCPU.scala:589-600 | 简化 SB→AXISQ 路径 |
| Branch 仅 lane0 验证 | lane0 计算 actual target | Execute.scala:38-52 | TD-C 后 lane1 也可执行 Branch，但仍受 doubleBrStall 单仲裁约束 |

---

## 十、配置常量速查（`CPUConfig.scala`）

| 参数 | 值 | 位宽 | 备注 |
|---|---|---|---|
| `issueWidth` | 4 | - | bundle 宽度 |
| `commitWidth` | 4 | - | = issueWidth |
| `renameWidth` | 4 | - | = issueWidth |
| `fetchWidth` | 4 | - | = issueWidth |
| `issueQueueEntries` | 48 | `iqIdxWidth=6` | v19 升 |
| `instSeqWidth` | 8 | - | 循环序号 |
| `robEntries` | 128 | `robIdxWidth=7` | |
| `robPtrWidth` | 8 | - | 含回绕位 |
| `prfEntries` | 128 | `prfAddrWidth=7` | p0 硬连 0 |
| `freeListEntries` | 96 | - | = prfEntries - 32 |
| `maxBranchCheckpoints` | 8 | `ckptIdxWidth=3` | BCT 深度 |
| `ckptPtrWidth` | 4 | - | 含回绕位 |
| `ckptSaveWidth` | 2 | - | 每拍最多保 2 快照 |
| `sbEntries` | 16 | `sbIdxWidth=4` | StoreBuffer |
| `storeSeqWidth` | 8 | - | Store 循环序号 |
| `fetchBufferEntries` | 32 | - | v19 升 |
| `bhtEntries` | 64 | - | PC[7:2] 索引 |
| `btbEntries` | 32 | - | PC 低位索引 |
| `refreshWidth` | 4 | - | PRF 写口数 |
| `prfReadPorts` | 8 | - | = 2 × issueWidth |
| `prfWritePorts` | 4 | - | = refreshWidth |
| `memoryWidth` | 4 | - | Memory bundle 通道数 |
| `loadLanes` | `Seq(0,1)` | - | TD-B 后 lane1 升 Full |
| `storeLanes` | `Seq(0,1)` | - | lane1 永不产 Store（IQ 仲裁规则）|
| `branchLanes` | `Seq(0,1)` | - | TD-C 后 lane1 接 Branch/JALR |

---

## 十一、当前 IPC 实测快照（v20，lane0/2/3 βwake 启用后）

> 数据源：`difftest/build/sim-data/<case>.csv` 末行 ipc 字段（sim-regressive 70/70 PASS 后落盘）。

### 11.1 高 IPC 用例（数据并行 + 弱依赖）
| TC | IPC |
|---|---|
| kernels_dep_chain_ooo4_unroll | **2.0631** |
| kernels_load_latency_hide_ooo4_unroll | 1.8053 |
| kernels_load_latency_hide_baseline | 1.7266 |
| kernels_dep_chain_baseline | ~1.61 |
| kernels_sum_unroll_ooo4_unroll | 1.4715 |
| kernels_memcpy_like_ooo4_unroll | 1.3680 |
| kernels_memcpy_like_baseline | 1.2733 |
| kernels_aos_vs_soa_ooo4_unroll | 1.2625 |

### 11.2 中等 IPC 用例（混合负载）
| TC | IPC |
|---|---|
| kernels_sum_unroll_baseline | ~1.25 |
| algo_array_ops_baseline | 1.1146 |
| algo_bst_ops_baseline | ~1.26 |
| algo_array_ops_ooo4_unroll | 0.8553 |
| algo_bst_ops_ooo4_unroll | 0.8593 |
| algo_list_dedup_ooo4_unroll | 0.8217 |
| algo_list_search_ooo4_unroll | 0.8388 |

### 11.3 低 IPC 用例（控制流 / 间接跳转 / 长依赖）
| TC | IPC | 主要瓶颈推测 |
|---|---|---|
| kernels_indirect_call_ooo4_unroll | 0.4141 | JALR mispredict（BTB 容量小，无 RAS） |
| algo_list_sort_ooo4_unroll | 0.6143 | 长指针追逐 + Branch heavy |
| algo_list_reverse_ooo4_unroll | 0.7489 | 同上（也是 Bug B 触发用例）|
| kernels_branch_reduce_ooo4_unroll | 0.8089 | 高密度 B-type，doubleBrStall 阻塞 |
| beq/bge/bne 单测 | 0.45~0.49 | 单测自带 mispredict |

### 11.4 v18 / v19.3 / v20 关键 TC 对比
| TC | v18 | v19.3 | **v20** | v20 vs v18 | v20 vs v19.3 |
|---|---|---|---|---|---|
| algo_array_ops_ooo4_unroll | 0.8910 | 0.7919 | **0.8553** | −4.0% | **+8.0%** |
| algo_array_ops_baseline | 1.0982 | 1.0946 | **1.1146** | +1.5% | +1.8% |
| kernels_load_latency_hide_ooo4_unroll | 1.7616 | 1.6883 | **1.8053** | **+2.5%** | +6.9% |
| kernels_load_latency_hide_baseline | 1.5444 | 1.5153 | **1.7266** | **+11.8%** | +14.0% |
| kernels_memcpy_like_ooo4_unroll | 1.2478 | 1.2201 | **1.3680** | **+9.6%** | +12.1% |
| kernels_memcpy_like_baseline | 1.1498 | 1.1468 | **1.2733** | **+10.7%** | +11.0% |
| kernels_dep_chain_ooo4_unroll | — | 1.3961 | **2.0631** | — | **+47.8%** |
| kernels_sum_unroll_ooo4_unroll | — | 1.3183 | **1.4715** | — | +11.6% |
| kernels_aos_vs_soa_ooo4_unroll | 1.2642 | 1.2642 | **1.2625** | −0.1% | −0.1% |

> **v20 关键收获**：
> - 大多数 load-latency / memcpy / dep_chain 类用例不仅恢复 v18 水平，还**显著超越** v18。
> - `algo_array_ops_ooo4_unroll` 仍 −4% vs v18（从 −11.1% 缩到 −4.0%）：剩余差距推测来自 lane1 βwake 仍禁用，待 P1。
> - `kernels_dep_chain_ooo4_unroll` +47.8% vs v19.3：长依赖链 ALU 生产者→消费者 RAW 路径直接受益于 lane2/3 βwake（典型场景）。

---

## 十二、v21+ 优化路径（按 IPC 杠杆排序）

### P1：lane1 βwake 启用（中杠杆 / 中风险）
- 现状：`betaEnable = (k.U === 0.U) || (k.U === 2.U) || (k.U === 3.U)`；lane1 仍禁用；
- 改动：`betaEnable = (k.U =/= 1.U) || true.B`（=全开）或独立验证 lane1 ALU producer N+3 假设；
- 风险：lane1 ALU 生产者→消费者 RAW 时序需独立波形验证（lane1 在 Issue/RR 阶段的下游路径 vs lane0 是否有差异）；
- 预期增益：算 algo_array_ops_ooo4_unroll 剩余 −4% gap 是否能补；
- 前置：建议先 P3（Bug B 深挖），避免 lane1 βwake 暴露隐藏 bug。

### P2：精确 lookahead 替换 `!anyLoadInExBundle`（高杠杆 / 高风险）
- 思路：把第 3 重门控替换为 `!willMemStallNextCycle`，仅在"该 bundle 实际会触发 memStall"时禁 βwake；
- Load 不必然 stall：full-cover Load、SB 命中、MSHR 有空都不 stall；
- 实现要求：与 `Memory.memStall` 共享判定逻辑（不能漂移）；
- 预期增益：含 Load 但不 stall 的 bundle 也能放 βwake → 进一步恢复 load-heavy 用例 IPC；
- 风险：lookahead 时序路径长，可能影响综合频率。

### P3：Bug B 根因深挖
- `algo_list_reverse_ooo4_unroll` 在 βwake 全禁场景 FAIL（PC=910 vs REF=6e4）；
- 当前 v20 βwake on (lane0/2/3) 不暴露；P1 全开后可能再次暴露；
- 解决后才能放心 P1/P2。

### P4：TD-F 双发 Branch（高杠杆 / 高风险）
- 解除 `doubleBrStall`；
- 需 Memory.redirect 升级多端口仲裁 + ROB rollback per-lane + BCT 双 ckpt 同拍保存；
- 预期增益：kernels_branch_reduce / algo_list_* 类（B-type 密集）+10~20%。

### P5：BTB / BHT 容量与 RAS（针对 indirect_call）
- 现 BTB=32 项 PC 低位索引；indirect_call IPC 仅 0.41；
- 候选：BTB 升 64/128 + RAS（Return Address Stack）8 项；
- 预期增益：kernels_indirect_call_ooo4_unroll 0.41 → 0.6+。

### P6：lane2/lane3 启用全功能（远期 / TD-G）
- 当前 ALU-only；改 Full 需扩 loadLanes/storeLanes/branchLanes 到 4，影响面巨大；
- 不在当前周期考虑。

---

## 十三、命令速查

```bash
# 编译/测试目录（必须）
cd /home/litian/Documents/stageFiles/studyplace/difftest

SIM=verilator xmake b rtl              # ~16s 生成并后处理 SV
SIM=verilator xmake b Core             # ~140s 全量 / ~40s 增量
SIM=verilator xmake r sim-basic        # 39 例 ~150s
SIM=verilator xmake r sim-regressive   # 70 例 ~5min

# 单测：DUMP=1 出 .vcd 到 build/verilator/Core/<TC>.vcd
SIM=verilator TC=<name> [DUMP=1 TIMEOUT=N] xmake r Core
```

---

## 十四、接手必读

1. 本文件（`scripts/STUDY.md`）—— 当前架构与 IPC 快照；
2. `TASK.md` —— 当前迭代任务；
3. `scripts/PLAN.md` 顶部里程碑 —— 整体路线图；
4. `scripts/wave-debug/SKILL.md` —— **任何波形调试前必读**；
5. `scripts/BUG.md` —— 已发现 BUG 与修复实证；
6. `scripts/LOG.md` —— 时间序施工日志（追溯历史决策时查）。
