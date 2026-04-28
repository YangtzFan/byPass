# 02 · 重命名（Rename + RAT + FreeList + ReadyTable）

> **角色**：消除 WAW / WAR 假依赖，把 32 个逻辑寄存器映射到 128 个物理寄存器；同拍维护 ReadyTable，为 IQ 提供初始 src ready 信号；为 Branch 提前抓 checkpoint。

## 1. 涉及源文件

| 文件 | 角色 |
|---|---|
| `dataLane/Rename.scala` | 4 lane 并发重命名主体 |
| `device/RAT.scala` | Register Alias Table（32 → 128 映射）|
| `device/FreeList.scala` | 96 项物理寄存器空闲列表（`prfEntries - 32`）|
| `device/ReadyTable.scala` | 128-bit pdst ready 状态 |

## 2. RAT —— 32 → 128 映射

- 32 项，每项 `prfAddrWidth=7` 位指向某 p<i>；
- **4 lane 并发查询**：每 lane 同时读 `rs1Logic` / `rs2Logic` 对应物理号；
- **同拍 rename bypass**：lane k 的 rs<n> 若与更老 lane (j<k) 的 rd 撞名，使用 lane j 同拍分配的 newPdst 而非旧表项；
- **同拍写**：4 lane rename 全部成功时，新 mapping 同拍写入 RAT（写优先 bypass）。

## 3. FreeList —— 96 项空闲

- 容量：`freeListEntries = prfEntries - 32 = 96`（p1~p32 之外的 96 个）；
- `allocOffsets(k)`：前 k 条指令累计需要分配的物理寄存器数（rd 非 x0 才需要）；
- **all-or-none**：若 freeCnt < 4-bundle 整体所需，整 bundle stall（不允许部分 rename）；
- BCT 快照保存 `FreeList.head` 指针；恢复时回滚到 ckpt.head。

## 4. ReadyTable —— 128-bit ready 位

- 每个物理寄存器 1 bit，1=ready；
- **置 busy**：rename 成功 → 该 newPdst 立即标 0（busy）；
- **置 ready**：同拍 Refresh 写 PRF → 同步置 1（v14 不变量：BCT 快照同拍 busyVen 旁路）；
- 既要响应 Dispatch 端的 src ready 初始化，又要响应 Refresh 端的就绪通知。

## 5. v14 关键不变量：BCT ReadyTable 快照同拍 busyVen 旁路

> Branch checkpoint 抓取的 ReadyTable 必须**包含**同拍 rename 新分配 pdst 的 busy 状态。否则恢复时旧 pdst 的 ready 位被错误保留，下游消费者直接读 PRF 拿到陈旧数据。

实现：`ReadyTable.scala` 中 ckpt 写口与 readyVen / busyVen 同拍 OR/AND 合并后再快照。

## 6. Branch checkpoint 抓取（与 Rename 同拍）

- B-type / JALR 指令进入 Rename 时同拍触发 ckpt；
- 每拍最多保 2 个（`ckptSaveWidth = 2`）；
- 快照内容：RAT 全表 + FreeList.head + ReadyTable 128-bit；
- 满（`ckptIdxWidth=3 → 8 项`）时整 bundle stall。

## 7. 关键不变量

1. **v14**：BCT ReadyTable 快照同拍 busyVen 旁路；
2. **all-or-none rename**：FreeList 不足或 ckpt 满 → 整 bundle stall；
3. **RAT 同拍 bypass**：lane j (j<k) 若 rd=lane k.rs<n>，lane k 用 j.newPdst；
4. **p0 硬连 0**：rd=x0 不分配 pdst，rs=x0 直接走 p0（PRF 第 0 项硬 0）。

## 8. 调试切入点

- 数据错误怀疑映射：dump RAT 全表 + 该指令 rs/rd logic→phys；
- IPC 偏低怀疑 freelist 不足：监控 `FreeList.freeCnt` 是否长期 < 4；
- Branch 恢复后取错值：检查 BCT ckpt 内 ReadyTable 与 RAT 是否一致（v14 不变量是否破坏）。
