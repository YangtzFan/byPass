# 12 · 存储子系统（StoreBuffer / AXIStoreQueue / DRAM / IROM）

> **角色**：Store 投机缓存 + commit 后写 AXI；Load 多源转发；DRAM AXI 接口（outstanding=2）；IROM 取指 ROM。

## 1. 源文件

| 文件 | 角色 |
|---|---|
| `dataLane/StoreBuffer.scala` | 投机 Store 缓冲（深度 16，字节级）|
| `memory/AXIStoreQueue.scala` | committed-store 等待 DRAM 写入（深度 16）|
| `memory/DRAM_AXIInterface.scala` | AXI outstanding=2，readReqFifo / readRespFifo 各 2 项 |
| `memory/IROM.scala` | 取指 ROM；通过 difftest xmake.lua `$readmemh` 注入 hex |

## 2. StoreBuffer（深度 16，投机）

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
ExMemDff.lane (sType) → SB.write（按 sbIdx）
ROB head 提交（lane0 + Store） → SB.commit (置 committed=1)
SB.dequeue（committed 优先）→ AXIStoreQueue.write
mispredict → drop storeSeq > storeSeqSnap 的项
```

## 3. AXIStoreQueue（深度 16，committed）

- 仅持已提交 Store；
- 提供 **committed-store forward 接口**给 Memory.scala 的 Load 转发链；
- `loadInflightCnt`：未完成 Load 计数，与 AXI outstanding=2 配合。

## 4. DRAM_AXIInterface

- AXI outstanding=2（最多 2 个 in-flight read/write）；
- `readReqFifo` / `readRespFifo` 各 2 项；
- 写路径：AXIStoreQueue → DRAM.write；
- 读路径：Memory MSHR → DRAM.read → mshrComplete → Refresh。

## 5. IROM

- 取指 ROM，4-wide 一拍出 128 bit；
- 内容由 difftest 平台在 xmake.lua 通过 `io.replace` 把 IROM `$readmemh` 路径替换成 `code.hex`（v18 不变量）；
- 不参与 mispredict / squash（只读）。

## 6. Load 字节合并 —— `Memory.scala mergeLoadSources`（~ line 130）

优先级：
```
StoreBuffer (投机)  >  AXIStoreQueue (committed)  >  DRAM
```

按 4 byte mask 字节级合并：每个字节单独比较（哪个源有 newer write 用谁的）。

## 7. 关键不变量

1. **v18**：difftest emu.lua dmem 启动从 .bin 预载 + xmake.lua 注入 `mem_65536x32.sv $readmemh dram.hex` + main.lua mismatch 用 `or` 累加；
2. **storeSeqSnap 精确回滚**：mispredict 时 SB 内 storeSeq > snap 全部丢；
3. **committed-store 不可丢**：进了 AXIStoreQueue 必须最终写入 DRAM；
4. **SB / AXISQ / DRAM 字节合并优先级**：必须严格按"newer wins"，否则 Load 拿到 stale 值。

## 8. 调试切入点

- Store 数据丢失：先确认是 SB 满阻塞 → AXISQ 满 → DRAM AXI 反压哪一档；
- Load 拿 stale 值：检查 mergeLoadSources 三源 mask 与优先级；
- IROM 拿到错指令：检查 difftest xmake.lua hex 注入路径。
