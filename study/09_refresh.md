# 09 · Refresh（PRF 写 + per-lane 仲裁 + ROB done）

> **角色**：4 个 PRF 写口，仲裁 mshrComplete Vec(2)（来自 MSHR）+ 普通 Refresh 通道（来自 Memory bundle 输出）；同拍置 ReadyTable / ROB.done。

## 1. 源文件

- `dataLane/Refresh.scala`
- 顶层仲裁：`MyCPU.scala:419-515`（"双 j 仲裁"）

## 2. 数据来源（两路合并）

```
mshrComplete(0)  ┐
mshrComplete(1)  │   →  MyCPU 顶层"双 j 仲裁"  →  effRef* per-lane 4 写口
普通 Refresh 通道 ┘
（来自 Memory.bundle.lanes(k).validMask + data）
```

## 3. 双 j 仲裁（`MyCPU.scala:419-515`）

```scala
mshrLaneClaimByJ(j)(k) = 1   // mshrComplete(j) 占 lane k
// 仲裁顺序：j=0 先选；j=1 后选（避开 j=0 已占）
// 普通 Refresh 那条若被 mshr 占走，移到下一可用 lane
```

- 4 个写口共享：mshr 0/1/2/3 占哪两个 + 普通 Refresh 占其余 ≤4 个；
- 全部冲突时：普通 Refresh 那条本拍不完成，下一拍重试。

## 4. 写 PRF + 同步置 ready

```scala
io.prfWrite(k).en := effRef(k).valid
io.prfWrite(k).addr := effRef(k).pdst
io.prfWrite(k).data := effRef(k).data

// 同拍置 ReadyTable（α-wake 来源）
io.readyTable.set(k).en := effRef(k).valid
io.readyTable.set(k).addr := effRef(k).pdst
```

## 5. ROB done

- per-lane 标 `rob[robIdx].done := true`；
- ROB 提交时按 head 顺序检查 done（Store 仅 lane0 提交）。

## 6. αwake 关系

- Refresh 同拍广播 pdst 给 IQ → IQ 同拍命中 + 下一拍 srcReady=1（Reg 翻转）；
- 这是最稳的一档 wakeup（生产者本拍真在写）。

## 7. 关键不变量

1. **TD-D**：`mshrComplete` Vec(2) per-slot 独立 ack；任一 slot ack 失败必须保留 in-flight；
2. **双 j 仲裁**：mshr 0/1 优先级在普通 Refresh 之上；
3. **同拍 ReadyTable 置位**：αwake 与 PRF 写同拍，IQ 入队 src ready 必须叠同拍 wakeup（见 04_issue_queue.md）。

## 8. 调试切入点

- ROB 不提交：dump 该 robIdx.done 是否被置；查 effRef 是否到达；
- αwake 漏唤醒：查 IQ 入队点是否叠加同拍 wakeup（v18 有相关修复，见 LOG.md）；
- mshrComplete drop：检查 ack/ready 握手是否被某拍 mshrLaneClaim 全冲突踢飞。
