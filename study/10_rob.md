# 10 · ROB（Reorder Buffer，128 项）

> **角色**：维护程序顺序；4 lane 并发 dispatch 入 tail，4 lane Refresh 标 done，按 head 顺序提交（Store 仅 lane0 可提交）。

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

Commit (按 head 顺序) → 检查 head.done, head+1.done, head+2.done, head+3.done
                       │
                       ├─ Store 仅 head 是 lane0 才提交
                       ├─ FreeList.dealloc(oldPdst)
                       └─ head += PopCount(commitMask)
```

## 5. Store 提交规则

- ROB.scala:69-78 + MyCPU.scala:589-600：Store 仅在 ROB head 是 lane0 时提交；
- 若 head 不是 lane0 但是 Store → 整拍不提交 Store（等待）；
- 简化 SB → AXIStoreQueue 时序（每拍最多一条 Store 出 SB）。

## 6. Branch 提交

- 在 ROB head 提交时验证 `predTaken === actualTaken`；
- 通常 Memory.redirect 已经在 mispredict 当拍触发，等到 ROB 提交时只是清理；
- mispredict 走 BCT 恢复路径而非 ROB（见 11_branch_checkpoint.md）。

## 7. 关键不变量

1. **all-or-none dispatch**：4 lane 必须同时入 tail（不允许部分 dispatch）；
2. **顺序提交**：head 必须先 done 才能提交，head 之后的 done 等待 head；
3. **Store 仅 lane0 提交**：ROB head 槽不是 lane0 但是 Store 则等待。

## 8. 调试切入点

- 单测 hang 第一步：dump `ROB.head` / `ROB.tail` / `head.done` / `head.instType`；
- IPC 偏低且 ROB 满：后端不及时 Refresh.done → 查 Memory MSHR 是否积压；
- Store 不提交：检查 head 槽 lane index 是否始终 ≠ 0（dispatch 顺序问题）。
