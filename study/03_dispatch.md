# 03 · 派发（Dispatch）

> **角色**：把 4 lane 重命名后的指令同拍写入 IQ 空闲槽 / ROB tail / SB 槽；同时初始化 IQ 中的 src{1,2}Ready（通过查 ReadyTable + 叠加同拍 wakeup）。

## 1. 涉及源文件

| 文件 | 角色 |
|---|---|
| `dataLane/Dispatch.scala` | 派发主体 |
| `device/ROB.scala` | ROB tail 分配 |
| `device/StoreBuffer.scala` | Store 槽分配（与 storeSeqSnap 同步）|
| `dataLane/IssueQueue.scala:153-167` | IQ 入队 + 初始 src ready |

## 2. 数据通路

```
RenDisDff.out (4 lane, 已 renamed)
        │
        ├─ ROB.dispatch(4)：分配 4 个连续 tail（按 lane index 偏移）
        ├─ SB.allocate：仅 sType lane 占 SB 槽（不连续）+ snap storeSeqWidth
        └─ IQ.enqueue：找 4 个空闲槽（FreeList 风格 PriorityEncoder）
                │
                ├─ src1Ready := ReadyTable[psrc1] || 同拍 wakeup hit
                ├─ src2Ready := ReadyTable[psrc2] || 同拍 wakeup hit
                └─ 余下字段：pdst, robIdx, sbIdx, instSeq, type bits...
```

## 3. ROB tail 分配

- 每拍 4 个连续 ROB 槽（`tail`, `tail+1`, `tail+2`, `tail+3`）；
- `validMask` 决定哪几个真正 dispatch；
- 满（`tail-head = robEntries`）时整 bundle stall。

## 4. SB 槽分配（仅 Store）

- 仅 `sType` lane 才占 SB；
- SB 是 16 项环形，按 storeSeq 顺序占用；
- `storeSeqSnap`：与该 Store 入 SB 同拍保存的 storeSeq，供分支恢复回滚使用。

## 5. IQ 初始 src ready —— `IssueQueue.scala:153-167`

```scala
// 关键逻辑：入队时查 ReadyTable + 叠加同拍 4×3 路 wakeup 命中
val src1ReadyInit = readyTableLookup(psrc1) || sameCycleWakeupHit(psrc1)
```

- **必须叠加同拍 wakeup**：否则刚 rename 进来的指令拿到的是上一拍 ReadyTable，错过当拍 Refresh / β / γ 广播 → IPC 退化；
- 此处不区分 α/β/γ 来源，只看是否撞 pdst。

## 6. 关键不变量

1. **all-or-none dispatch**：4 lane 中任一项资源不足（IQ 槽 / ROB tail / SB 槽 / FreeList / BCT 快照）→ 整 bundle stall；
2. **storeSeqSnap 与 SB 槽同拍**：恢复时按 snap 精确回滚；
3. **IQ 入队 src ready 必须叠同拍 wakeup**：否则错过单拍唤醒。

## 7. 调试切入点

- IPC 异常时检查 IQ 占用率：长期 < 4 槽空闲说明 dispatch 出口被 IQ 满阻塞；
- Store 不提交：检查 SB 是否满（v18 之前曾有 SB 写满后 dispatch 阻塞 commit 的死锁）；
- 单测 hang：先看 `Dispatch.in.ready` / `out.valid`，定位是 IQ 满 / ROB 满 / FreeList 不足 / BCT 满 哪一档。
