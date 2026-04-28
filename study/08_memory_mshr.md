# 08 · Memory（非阻塞 Load + 双 capture MSHR + AR 仲裁）

> **角色**：bundle 内最复杂的模块（~755 行）。Store 走 SB；Load 走 SB 转发 → AXIStoreQueue 转发 → MSHR fresh AR / pending AR；MSHR 结果通过 mshrComplete Vec(2) 走专用 Refresh 路径写 PRF。

## 1. 源文件

- `dataLane/Memory.scala`（~755 行，最大单文件）
- 顶层接线 / γwake 抑制 / 双 j 仲裁：`MyCPU.scala:327-515`

## 2. Store 路径（line 200-215）

```
ExMemDff.out (sType lane)
    ↓
StoreBuffer.write：byte mask + data + storeSeqSnap
    ↓
后续随 ROB 提交搬到 AXIStoreQueue
```

- 仅 lane0 / lane1 可产 Store；
- **Store 提交仅 lane0**（ROB.scala:69-78）—— 简化 SB→AXISQ 时序。

## 3. Load 路径（line 218-450）—— 多源合并

合并优先级（`mergeLoadSources` ~ line 130）：

```
SB（投机 + 待提交） > AXIStoreQueue（committed-store 等待写入）> DRAM
```

按字节 mask 合并：哪个字节有更近的 store 就用谁的 data。

### 3.1 olderUnknown 检测

- 若有更老的 Store 但**地址未知**（rs1 还没 ready 算出 addr）→ `anyOlderUnk` → 整 bundle stall（Memory in.ready=0），等地址。

### 3.2 全覆盖判定

- SB+AXISQ 字节 mask 是否完全覆盖 Load 4 byte → 全覆盖直接出结果，不发 AR；
- 不全覆盖 → 走 fresh AR / pending AR 路径。

## 4. MSHR Vec(2)（双 capture）

| 信号 | 说明 |
|---|---|
| `freeMask` | 2-bit，1=空闲槽 |
| `PopCount(freeMask) >= numNeedDram` | all-or-none accept（line 429）|
| `captureSlotForLane` | lane0 → `allocSlot`（最低空闲）；lane1 → `allocSlot1`（次低空闲，仅 lane0 也 capture 时）|
| `mshrCaptureFire(j)` | per-lane Bool，"该 lane 已被 MSHR 接管"（即使 AR 尚未发）|
| `mshrInFlightFifo` | 按 capture 顺序记录 in-flight order，DRAM 返回时按 FIFO 头匹配 |

### 4.1 AR 仲裁

```scala
launchPendingAR > launchFreshAR  // pending 优先
freshFireLane = PriorityEncoder(captureAcceptedVec)  // fresh 时取最低 lane
```

- 其余 fresh capture 设 `mshrArPending = true`，下一拍 `launchPendingAR` 补发；
- AR outstanding 由 DRAM_AXIInterface 限制为 2。

## 5. memStall 三态（line 466~）

| 条件 | 行为 |
|---|---|
| `anyOlderUnk` | 整 bundle stall（in.ready=0）|
| `numNeedDram=2 && freeCnt<2` | 整 bundle stall（all-or-none rule）|
| `anyCaptureAccepted` | 放行（fresh 已成功 capture，下游可走 outMaskBits 扣除 capture lane）|
| 全覆盖 / 无 Load | 自然推进 |

## 6. mshrComplete Vec(2)（TD-D 不变量）

```scala
io.mshrComplete: Vec(2, Decoupled(MshrCompleteBundle))
```

- 每个 slot 独立 ack 给 MyCPU；
- MyCPU 顶层做"双 j 仲裁"决定哪条 mshrComplete 占哪个 Refresh lane（详见 09_refresh.md）。

## 7. outMaskBits 扣除

- `mshrCaptureFire(j)=1` 时，bundle 输出 lane k=loadLanes(j) 的 validMask 在 `outMaskBits` 中清零；
- 防止 Refresh 把 Load 当 ALU 结果（"地址当数据"）写 PRF；
- **真实数据后续由 mshrComplete 路径补回**（独立 Refresh 写口）。

## 8. 关键不变量（**最严格**）

1. **I-A bundle 原子性**：`in.ready=false` 时严禁 fresh MSHR alloc / fresh AR fire / mshrInFlightFifo enq；
2. **I-B 双 capture all-or-none**：`numNeedDram=2 && freeCnt<2` 必整拍 stall，capture 必须 0 或 2；
3. **`mshrCaptureFireWire === anyCaptureAccepted`**（line 671-744 断言守护）；
4. **TD-D**：`mshrComplete` Vec(2)、per-slot 独立 ack。

## 9. 不变量断言（line 671-754）

源码内置断言覆盖：
- bundle 原子性
- 双 lane capture 必进不同 MSHR 槽（不能撞）
- `mshrCaptureFireWire === anyCaptureAccepted`
- mshrInFlightFifo 容量与 in-flight 数一致

回归任何 FAIL 必须先复查这些断言。

## 10. 调试切入点

- Load 数据错：先看 `mergeLoadSources` 的 SB / AXISQ / DRAM 三源覆盖 mask；
- MSHR hang：检查 `freeMask` / `mshrInFlightFifo.size` / AR outstanding；
- Refresh 把 Load 写错位：检查 `outMaskBits` 是否扣除了 capture lane（I-A 不变量）。
