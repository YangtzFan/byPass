# 12 · 存储子系统（StoreBuffer / AXIStoreQueue / I-Cache / D-Cache / DRAM / IROM）

> **角色**：Store 投机缓存 + commit 后写 AXI；Load 多源转发；I-Cache / D-Cache 命中/refill；DRAM AXI 接口；IROM 取指 ROM。

## 1. 源文件

| 文件 | 角色 |
|---|---|
| `dataLane/StoreBuffer.scala` | 投机 Store 缓冲（深度 16，字节级，commit 接口 Vec(K=2)）|
| `memory/AXIStoreQueue.scala` | committed-store 等待 DRAM 写入（深度 16，enq 接口 Vec(K=2)）|
| `memory/ICache.scala` / `memory/DCache.scala` | 写直通 I-Cache / D-Cache，4 way / sets 由 CPUConfig 派生 |
| `memory/LatencyPipe.scala` | 统一硬延迟模型（N 级 pipe=true Queue 串联）|
| `memory/AXIToDCacheBridge.scala` | DRAM AXI ↔ D-Cache 桥 |
| `memory/DRAM_AXIInterface.scala` | AXI 读 outstanding=2，写单 outstanding |
| `memory/IROM.scala` | 取指 ROM；通过 difftest xmake.lua `$readmemh` 注入 hex |

## 2. StoreBuffer（深度 16，投机，commit Vec(K=2)）

| 配置 | 值 |
|---|---|
| `sbEntries` | 16 |
| `sbIdxWidth` | 4 |
| `storeSeqWidth` | 8（循环 store 序号）|

字段每项：
- `addr`、`mask` (4 byte)、`data` (32 bit)
- `committed`：是否已经被 ROB 提交（commit 时置）
- `storeSeq`：进 SB 时打的序号（mispredict 时按 storeSeqSnap 回滚）

数据通路：
```
ExMemDff.lane (sType) → SB.write（按 sbIdx，2 lane 同拍写入）
ROB head 提交（storeLanes 任一路） → SB.commit(k)（Vec(K=2)，置 committed=1）
SB.dequeue（committed 优先）→ AXIStoreQueue.enq(k)
mispredict → drop storeSeq > storeSeqSnap 的项
```

## 3. AXIStoreQueue（深度 16，committed，enq Vec(K=2)）

- 仅持已提交 Store；
- enq 端口 `Vec(K=2)` + PriorityEncoderOH 紧凑写入：本拍最多入队 2 条，按 lane 顺序占用 tail/tail+1，`count` 推进 = `count + PopCount(enqFireVec)`；
- `enq(k).ready := count + k.U < depth.U` 保守判定（在 preserve_rob_lane_position 约束下永不浪费容量）；
- 提供 **committed-store forward 接口**给 Memory.scala 的 Load 转发链；
- `loadInflightCnt`：未完成 Load 计数，与 AXI 读 outstanding=2 配合；
- **debug IO（Vec(K)）**：每路 enq.fire 那拍把 `addr/data/mask` 暴露到 `axiStoreQueue.debug.lane(k).commitRamW*`，供 SoC_Top 路由到 `io.debug_commit(*)` 对齐 difftest 观察。

## 4. I-Cache / D-Cache（写直通，LatencyPipe 串联）

- 当前几何（CPUConfig.scala）：`icacheSize = 4096`、`dcacheSize = 4096`、`cacheLineBytes = 64`（写死，= 16 word = 4 × 128bit）、`cacheAssoc = 4`；
- I-Cache 命中：Fetch ↔ ICache 走 `qDepthCpuToCache = 4` 拍延迟（LatencyPipe）；
- I-Cache miss refill：ICache ↔ IROM 走 `LatencyPipe(qDepthCacheToMem = 200)`，单 outstanding refill；
- 关 I-Cache 旁路（`icacheSize = 0`）：Fetch ↔ IROM 直连 `LatencyPipe(qDepthCpuToMem = 200)`；
- D-Cache 接 AXIToDCacheBridge → DRAM_AXIInterface；
- 详细 I-Cache / D-Cache 微架构、几何参数、IPC 对比见 `study/18_cache_microarch.md`。

## 5. DRAM_AXIInterface

- AXI 读 outstanding=2（最多 2 个 in-flight read），写单 outstanding（B 串行）；
- `readReqFifo` / `readRespFifo` 各 2 项；
- 写路径：AXIStoreQueue → DRAM.write；
- 读路径：Memory MSHR → DRAM.read → mshrComplete → Refresh。

## 6. IROM

- 取指 ROM，4-wide 一拍出 128 bit；
- 内容由 difftest 平台在 xmake.lua 通过 `io.replace` 把 IROM `$readmemh` 路径替换成 `code.hex`（v18 不变量）；
- 不参与 mispredict / squash（只读）。

## 7. Load 字节合并 —— `Memory.scala mergeLoadSources`（~ line 130）

优先级：
```
StoreBuffer (投机)  >  AXIStoreQueue (committed)  >  DCache / DRAM
```

按 4 byte mask 字节级合并：每个字节单独比较（哪个源有 newer write 用谁的）。

## 8. 观察口语义（v22 Phase A.2）

difftest 比对每路 store 是否落入 mem 的观察点 —— `io.debug_commit(k).ram_w_*` —— 在 v22 已**前移到 AXIStoreQueue 入口**：

- SoC_Top 中：`io.debug_commit(k).ram_w_*` ⇐ `axiMasterDebug.lane(storeOrdinal(k)).commitRamW*`，其中 `storeOrdinal(k)` 由 ROB 输出，表示 commit bundle 中 lane k 在 store 序中的位置（0 或 1）；
- 该信号与 `AXIStoreQueue.enq(k).fire` / `ROB.commit(k).valid (store)` **严格同拍**；
- lane ∉ storeLanes 硬连 0（commitWidth=4，K=2 时 lane2/lane3 永远不是 store）。

设计意图：difftest 不再依赖原 SB→AXISQ 之间的中间状态，直接在"真正落入 AXISQ 队列那拍"采样 ram 地址/数据，与参考模型 `main.lua:225-285` 的 per-lane 迭代逻辑天然对齐。

## 9. 关键不变量

1. **v18**：difftest emu.lua dmem 启动从 .bin 预载 + xmake.lua 注入 `mem_65536x32.sv $readmemh dram.hex` + main.lua mismatch 用 `or` 累加；
2. **storeSeqSnap 精确回滚**：mispredict 时 SB 内 storeSeq > snap 全部丢；
3. **committed-store 不可丢**：进了 AXIStoreQueue 必须最终写入 DRAM；
4. **SB / AXISQ / DCache / DRAM 字节合并优先级**：必须严格按"newer wins"，否则 Load 拿到 stale 值；
5. **observation-port 同拍对齐（v22）**：`debug_commit(k).ram_*` 必须由 AXISQ 入口（enq.fire 那拍）的 debug 信号驱动，不允许引入额外 Reg 延迟。

## 10. 调试切入点

- Store 数据丢失：先确认是 SB 满阻塞 → AXISQ 满 → DRAM AXI 反压哪一档；
- Load 拿 stale 值：检查 mergeLoadSources 多源 mask 与优先级；
- IROM 拿到错指令：检查 difftest xmake.lua hex 注入路径；
- difftest store 比对失败：dump `axiMasterDebug.lane(k).commitRamW*` 与 `ROB.commit(k)` 同拍快照，确认 `storeOrdinal(k)` 路由是否正确。
