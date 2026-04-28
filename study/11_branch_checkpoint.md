# 11 · Branch Checkpoint Table（投机回滚）

> **角色**：在 Branch / JALR 进入 Rename 时同拍抓取微架构状态快照（RAT + FreeList.head + ReadyTable），mispredict 时单拍精确恢复。

## 1. 源文件

- `device/BranchCheckpointTable.scala`
- 重定向冲刷路径：`MyCPU.scala:370-406`

## 2. 容量

| 配置 | 值 |
|---|---|
| `maxBranchCheckpoints` | 8 |
| `ckptIdxWidth` | 3（log2(8)）|
| `ckptPtrWidth` | 4（含回绕位）|
| `ckptSaveWidth` | 2（每拍最多保 2 个）|

## 3. 快照内容（每项）

- `ratSnap`：RAT 全表 32 × 7 = 224 bit；
- `flHead`：FreeList.head 指针；
- `readyTableSnap`：128-bit pdst ready 状态（v14 已叠加同拍 busyVen 旁路）；
- `robIdx`：抓快照时该 Branch 的 ROB 槽号（恢复时基准）；
- `storeSeqSnap`：当时 Store 序号（用于 SB 精确回滚）。

## 4. 数据通路

```
Rename (Branch/JALR lane k)
    ↓
allocCkpt(k) := true（每拍最多 2 个）
    ↓
ckpt[ckptTail]<= { ratSnap, flHead, readyTableSnap, robIdx, storeSeqSnap }
ckptTail += PopCount(allocCkpt)

mispredict 触发：
    Memory.redirect(robIdx=memRedirectRobIdx)
    → BCT.recoverIdx = lookup(memRedirectRobIdx)
    → 单拍同步：
       RAT     := ckpt[recoverIdx].ratSnap
       FreeList.head := ckpt[recoverIdx].flHead
       refreshedSinceSnap[recoverIdx] OR readyTableSnap → ReadyTable

Branch 验证 PASS：
    BCT 释放该 ckpt（按 ckptHead 顺序），ckptHead += 1
```

## 5. `refreshedSinceSnap[idx]`

> 维护"快照后已 ready 的 pdst 位向量"。恢复时 OR 回 readyTableSnap，避免 stale。

- 每个 ckpt 一份 128-bit；
- 每拍 Refresh 写 PRF 时，对所有未释放的 ckpt 都 OR 一位（按 pdst）；
- 恢复时：`ReadyTable := readyTableSnap | refreshedSinceSnap[idx]`。

## 6. 重定向冲刷路径（`MyCPU.scala:370-406`）

```scala
def laneFlush(dff, memRedirectRobIdx):
  对每个 lane 比较 robIdx > memRedirectRobIdx → 清 validMask
  全 0 → 整 bundle 丢

应用范围：
- DecRenDff、RenDisDff、IssRRDff、RRExDff、ExMemDff、MemRefDff
- IQ.flush := true（全部 valid=0）
- FreeList.head / RAT 由 BCT.recoverIdx 恢复
- MSHR：清掉年轻于分支的 in-flight 条目（mshrFlushed[i] 标记）
- StoreBuffer：按 storeSeqSnap 精确回滚
```

## 7. 关键不变量

1. **v14**：BCT ReadyTable 快照同拍 busyVen 旁路；
2. **all-or-none allocCkpt**：BCT 满则整 bundle stall（Rename 反压）；
3. **MSHR 精确回滚**：年轻于分支的 in-flight Load 清除；mshrInFlightFifo 同步 squash；
4. **storeSeqSnap 精确回滚**：SB 中 storeSeq > snap 的全部丢弃。

## 8. 调试切入点

- mispredict 后取错值：检查 ReadyTable 恢复是否包含 `refreshedSinceSnap`（v14 不变量）；
- mispredict 后 hang：MSHR squash 是否清干净 + AXIStoreQueue 是否还在阻塞；
- BCT 满阻塞：算法密集 Branch 用例可触发；属正常反压。
