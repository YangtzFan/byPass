# 05 · Issue 多发射仲裁

> **角色**：在 IQ 给出 4 lane "candidate ready" 之后，根据**同 bundle 内的 hazard 规则**决定哪些 lane 真正发射。约束包括：lane 能力、`pairRaw`、`doubleBrStall`。

## 1. 源文件

- `dataLane/Issue.scala`（核心，~150 行）
- 配置：`dataLane/LaneCapability.scala:84-92`（lane 能力定义）

## 2. lane 能力

```
lane0 / lane1：Full（ALU + Load + Store + Branch + JALR）
lane2 / lane3：ALU-only（iType / rType / uType）

loadLanes  = Seq(0, 1)
storeLanes = Seq(0, 1)   // lane1 永不产 Store（IQ 仲裁规则）
branchLanes = Seq(0, 1)
```

## 3. 约束规则

### 3.1 `pairRaw`（line 100-116）—— Load-Use 单拍 RAW 不能同 bundle 解

- **触发**：lane j (j<k) 是 Load 且 lane k 任一 src 读 j.pdst；
- **后果**：lane k stall（不发射）；
- **原因**：Load N 拍发射，N+1 拍才出结果（Memory 阶段写 PRF 走 Refresh），同 bundle 内消费者无法及时拿到值。

### 3.2 `doubleBrStall`（line 118-141）—— Branch 单端口

- **触发**：lane j (j<k) 是 B-type / JALR，且 lane k 也是 B-type / JALR；
- **后果**：lane k stall；
- **原因**：Memory.redirect 是单端口，本周期最多容纳 1 条 Branch 验证结果。

### 3.3 `doubleLoadStall`（v19 TD-E 已删除）

- **历史**：v19 之前阻止 lane0/lane1 同拍各发一条 Load；
- **删除原因**：Memory 已支持双 capture / per-lane mshrCaptureFire / AR pending 仲裁，dual-Load 安全；
- **风险地图 R1~R5**：详见 `LOG.md` v19 章节。

## 4. 仲裁公式

```scala
finalIssue(k) := laneCanIssue(k) && !pairRaw(k) && !doubleBrStall(k)
out.valid := finalIssue.reduce(_ || _)   // 至少 1 lane 发射时整 bundle 推进
```

- `laneCanIssue(k)`：来自 IQ oldest-ready 输出；
- 完整 bundle 输出包含每 lane 的 valid（即 finalIssue(k)）+ 所有字段。

## 5. 关键不变量

1. **lane 能力静态约束**：IQ 仲裁阶段就排除 lane2/3 选中 Load/Store/Branch 类型；
2. **pairRaw 严格 j<k**：上游 lane 阻塞下游 lane，反向不阻塞；
3. **bundle 原子推进**：finalIssue 至少 1=1 → out.valid=1，下游接 IssRRDff；全 0 则该拍 IQ 不出。

## 6. 调试切入点

- IPC 偏低且 `branch_reduce` 类用例最严重 → `doubleBrStall` 频发 → 候选 P4（TD-F）双发 Branch；
- 怀疑漏发射：对比 IQ ready vs Issue out 的 lane mask 差异；
- v22+ 候选：`pairRaw` 改为"forwarding 链路"而非简单 stall。
