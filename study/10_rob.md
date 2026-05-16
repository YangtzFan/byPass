# 10 · ROB（Reorder Buffer，128 项）

> **角色**：维护程序顺序；4 lane 并发 dispatch 入 tail，4 lane Refresh 标 done，按 head 顺序提交（Store 同拍 ≤ K=2 个，仅 `storeLanes={0,1}` 允许产 Store）。

## 1. 源文件

- `device/ROB.scala`

## 2. 容量与位宽

| 配置 | 值 |
|---|---|
| `robEntries` | 128 |
| `robIdxWidth` | 7（log2(128)）|
| `robPtrWidth` | 8（含回绕位）|

`robPtrWidth = robIdxWidth + 1`：方便 head/tail 比较时区分"满"和"空"。

## 3. 字段（每项）

- `valid`、`done`
- `pdst`（用于提交时通知 RAT 释放旧 mapping → FreeList）
- `oldPdst`（rename 前 RAT 的旧映射）
- `instType`（Store / Branch / 其他）
- `sbIdx`（Store 才有效）
- `predTaken`、`actualTaken`（Branch 验证）
- ...

## 4. 数据通路

```
Dispatch (4 lane) → tail/tail+1/tail+2/tail+3 同拍写
                     │
                     ├─ tail += PopCount(validMask)
                     └─ 满（tail-head=128）→ stall

Refresh (4 lane) → robIdx.done := true（按各自 robIdx 写）

Commit (按 head 顺序) → 检查 head.done .. head+3.done
                       │
                       ├─ Store 允许出现在 lane0 / lane1（最多 2 个/拍）
                       ├─ 受 commitBlocked: Vec(K=2) 反压（AXISQ 不接收时该路 store 不退役）
                       ├─ canCommitVec(i) := prevCommitOk && headLaneReady(i) && !(isStore && commitBlocked(ord))
                       ├─ FreeList.dealloc(oldPdst)
                       └─ head += PopCount(commitMask)
```

## 5. Store 提交规则（v22 Phase A.2）

- ROB 端：`storeMustBeInStoreLaneSet` 取代旧版 `storeMustBeLane0Only` —— store 只在 `storeLanes={0,1}` 出现，编译期约束（dispatch 时 IQ 仲裁已保证）；
- 同拍允许 lane0 + lane1 两条 Store 同时退役（K=2）；
- 运行期：`commitBlocked: Vec(K, Bool)` 由 MyCPU 顶层根据 SB→AXISQ 每路 enq.ready 拉起 —— 任一路反压则该路的 store 整拍不退役；
- canCommitVec 串行链：`canCommitVec(i) := prevCommitOk && headLaneReadyHere(i) && !(isStore_i && commitBlocked(storeOrdinal(i)))` —— 防止 AXISQ 阻塞较老 store 时较年轻 lane 越位提交（保证 in-order commit 语义）；
- ROB 输出 `storeOrdinal(i): UInt`：当前 commit bundle 中 lane i 在 store 序中的位置（0 或 1），驱动 MyCPU.sqEnq(k) 路由与 SoC_Top 观察口选择。

## 6. Branch 提交

- 在 ROB head 提交时验证 `predTaken === actualTaken`；
- 通常 Memory.redirect 已经在 mispredict 当拍触发，等到 ROB 提交时只是清理；
- mispredict 走 BCT 恢复路径而非 ROB（见 11_branch_checkpoint.md）。

## 7. 观察口语义（v22 后端到端对齐）

`io.debug_commit(k).ram_*` —— difftest 比对 store 是否提交到 mem 的观察点 —— 在 v22 已**前移到 AXIStoreQueue 入口**：

- SoC_Top 中：`io.debug_commit(k).ram_w_*` ⇐ `axiMasterDebug.lane(storeOrdinal(k)).commitRamW*`；
- 与 `AXISQ.enq(k).fire` / `ROB.commit(k).valid (store)` **严格同拍**；
- lane ∉ storeLanes 时硬连 0（commitWidth=4，K=2 时 lane2/lane3 永远不是 store）。

这一改动让 difftest 不再依赖原 SB→AXISQ 之间的中间状态，直接在"真正落入 AXISQ 队列那拍"采样 ram 地址/数据，与参考模型同拍对齐。详见 `study/12_storage_subsystem.md` §观察口。

## 8. 关键不变量

1. **all-or-none dispatch**：4 lane 必须同时入 tail（不允许部分 dispatch）；
2. **顺序提交**：head 必须先 done 才能提交，head 之后的 done 等待 head；
3. **Store 仅在 storeLanes 退役**：dispatch 阶段已保证；
4. **canCommit 串行链**：lane i 提交隐含 lane 0..i-1 全部已通过 commitBlocked 门控（防 OOO commit）。

## 9. 调试切入点

- 单测 hang 第一步：dump `ROB.head` / `ROB.tail` / `head.done` / `head.instType`；
- IPC 偏低且 ROB 满：后端不及时 Refresh.done → 查 Memory MSHR 是否积压；
- Store 不提交：检查 `commitBlocked` 是否长期拉起（AXISQ 满或 AXI B 慢），以及 `storeOrdinal` 是否正确路由到 sqEnq(k)。
