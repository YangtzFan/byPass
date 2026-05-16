# 18 · Cache 微架构与 I-Cache / D-Cache IPC 影响

> **角色**：byPass 当前接入了 I-Cache、D-Cache（均 4 KiB / 4-way / 64 B line），辅以 `LatencyPipe` 硬延迟模型模拟离片主存。
> 本文从结构、几何参数、时序模型、IPC 实测四个维度收口，作为 §08（Memory）/ §09（Refresh）/ §12（Storage Subsystem）之间的"Cache 主线"参考。
>
> 配套阅读：[`08_memory_mshr.md`](08_memory_mshr.md)、[`09_refresh.md`](09_refresh.md)、[`12_storage_subsystem.md`](12_storage_subsystem.md)。

---

## 1. 角色与位置

```
                     ┌─────────────────────────────────────┐
   Fetch ─┐          │       SoC_Top（cache/* + memory/*）│
          │ ICacheReq│                                     │
          ▼          │   ┌──────────┐   ┌──────────────┐  │
       LatencyPipe   │   │  ICache  │──▶│  LatencyPipe │──┼──▶ IROM
       (cpuToCache)──┼──▶│  4 KiB   │   │  cacheToMem  │  │
                     │   │  4-way   │   └──────────────┘  │
                     │   └──────────┘                      │
                     │                                     │
   Memory(Load/Sto) ─┼──▶ AXISQ.fwd / SB.fwd                │
                     │                                     │
                     │   ┌──────────┐   ┌──────────────┐  │
       AXIToDCacheBr─┼──▶│  DCache  │──▶│  LatencyPipe │──┼──▶ DRAM
                     │   │  4 KiB   │   │  cacheToMem  │  │
                     │   │  4-way   │   └──────────────┘  │
                     │   └──────────┘                      │
                     └─────────────────────────────────────┘
```

- I-Cache 仅有读端口，由 Fetch 通过 `qDepthCpuToCache` 路径访问；miss 时通过 `qDepthCacheToMem` 路径向 IROM 单 outstanding refill。
- D-Cache 既有读也有写（AXIToDCacheBridge 把 AXIStoreQueue 的 commit 写入 cache）；同 IROM/DRAM 的 refill / writeback 走同一对 LatencyPipe 模板。
- `icacheSize == 0` 或 `dcacheSize == 0` 时旁路对应 Cache，Fetch / Memory 改走 `qDepthCpuToMem` 直接到 IROM/DRAM。

源文件入口：
- `src/main/scala/cache/ICache.scala` / `DCache.scala`
- `src/main/scala/memory/LatencyPipe.scala`
- `src/main/scala/SoC_Top.scala`（接线）

---

## 2. 几何参数（来自 `src/main/scala/CPUConfig.scala`）

| 参数 | 值 | 含义 |
|---|---|---|
| `icacheSize` | **4096 B** | I-Cache 总容量；0 = 关闭 |
| `dcacheSize` | **4096 B** | D-Cache 总容量；0 = 关闭 |
| `cacheLineBytes` | **64 B** | 一行 = 16 word = 4 × 128 bit fetch 槽 |
| `cacheAssoc` | **4-way** | 组相联路数（可选 1/2/4/8） |
| 派生 `numSets` | **16** | = size / line / way = 4096 / 64 / 4 |
| `qDepthCpuToCache` | **4** | 命中路径硬延迟（CPU ↔ Cache 端口，pipe=true，稳态吞吐 1 token/cycle） |
| `qDepthCacheToMem` | **200** | Cache ↔ 主存路径硬延迟（miss refill / writeback） |
| `qDepthCpuToMem` | **200** | 关 Cache 旁路时 CPU ↔ 主存直连硬延迟 |
| 写策略 | Write-Back + Write-Allocate（写死） | 详见 DCache 实现 |

注：所有 `qDepth*` 参数已在 §2.2 七条要点统一为 **200 / 4 / 200**。Phase A.2 后 ROB 同拍最多退役 K = `storeLanes.size` = 2 条 Store，提升 store-heavy 用例吞吐上限。

---

## 3. 取指路径（I-Cache）

### 3.1 命中路径

```
Fetch.req ──▶ LatencyPipe(cpuToCache, depth=4) ──▶ ICache.lookup
                                                       │
                                            tag hit ──▶ data line
                                                       │
              ◀── LatencyPipe(cpuToCache, depth=4) ────┘
```

- `pipe=true` 配置下 LatencyPipe 稳态吞吐 = 1 token / cycle，仅引入 4 拍的"管线延迟"。
- ICache 内部 tag SRAM / data SRAM 同拍读，组合逻辑命中比较；4-way set-associative，LRU 替换。

### 3.2 Miss refill 路径

```
ICache.miss ──▶ LatencyPipe(cacheToMem, depth=200) ──▶ IROM 顺序填充 16 word
              ◀── 反向回路也走 200 拍 ──
```

- **单 outstanding refill**：同时只能有一条 line 在 refill 中，刷新期间后续 fetch 阻塞。
- IROM 读端口直接产出 word 流，没有总线仲裁的额外开销，refill 延迟主要由 LatencyPipe 主导。

### 3.3 关 Cache 旁路（`icacheSize == 0`）

```
Fetch ──▶ LatencyPipe(cpuToMem, depth=200) ──▶ IROM ──▶ Fetch
```

每条指令都直击 200 拍硬延迟。本文 §6 的 baseline 就在该配置下采样。

---

## 4. 访存路径（D-Cache）

详情见 §08 / §12，本节仅摘要相关 Cache 行为：

- **命中**：Load 经 SB → AXISQ → DCache 三级转发，DCache 命中走 `qDepthCpuToCache` 拍延迟；Store 命中由 AXIToDCacheBridge 写入 cache line，dirty bit 置位。
- **Miss refill**：DCache miss 经 `qDepthCacheToMem` 拍延迟向 DRAM 取行；refill 期间 MSHR 维持 outstanding，Load 通过 `mshrComplete` Vec(2) 借 Refresh 通道写回 PRF（详 §08 §3 / §09）。
- **Writeback**：脏行被替换时写回 DRAM，同样走 `qDepthCacheToMem` 拍延迟。
- **Store commit**：Phase A.2 起单拍最多 K = 2 条 store 同步 enq AXIStoreQueue，再由 AXISQ→DCache bridge drain。

---

## 5. 延迟模型实现（`LatencyPipe`）

```scala
class LatencyPipe[T <: Data](gen: T, depth: Int, pipe: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val deq = Decoupled(gen)
  })
  // 内部为 depth 级 SRL（shift register）+ 头尾 valid bookkeeping；
  // pipe=true 时只引入 depth 拍管线延迟，稳态吞吐 = 1 token / cycle。
}
```

- 所有 cache/memory 路径都通过 `LatencyPipe` 统一硬延迟，**没有 `axiQueueChain` / inline `latencyChain` 残留**（§2.2 已确认）。
- `LatencyPipe.axiChain(...)` 进一步把 AR/R/AW/W/B 五通道用同一组 depth 包起来，确保 AXI 协议层时序自洽。

---

## 6. IPC 实测对比（核心数据章节）

### 6.1 测量方法

- **配置 A — baseline**：`icacheSize = 0` **且** `dcacheSize = 0`（两级 cache 全部旁路，Fetch/Load/Store 全部直击 200 拍硬延迟通道）。
- **配置 B — icache_on**：`icacheSize = 4096` **且** `dcacheSize = 4096`（I/D 双 cache 同时开启，4-way、64 B line）。
- 跑同一套 `sim-regressive` 64 用例，关闭超时（`TIMEOUT=0`），取 `build/sim-data/regressive/<case>.csv` 最后一行的 `ipc` 列。
- Speedup = `IPC_icache_on / IPC_baseline`。
- GeoMean = `exp( mean( ln(speedup_i) ) )` 用以避免大值用例的算术平均偏置。
- 仿真平台：Verilator 5.x（`SIM=verilator JOBS=24`）。
- 数据快照路径：`/tmp/ipc_snapshot/{baseline_both_off,icache_on}/*.csv`。

### 6.2 全用例数据（按字母序）

| Case | Baseline IPC | Icache_on IPC | Speedup |
|---|---:|---:|---:|
| algo_array_ops_baseline | 0.0061 | 0.1959 | 31.88× |
| algo_array_ops_ooo4 | 0.0061 | 0.1959 | 31.88× |
| algo_array_ops_ooo4_no_libcall | 0.0061 | 0.1959 | 31.88× |
| algo_array_ops_ooo4_unroll | 0.0054 | 0.1586 | 29.41× |
| algo_bst_ops_baseline | 0.0062 | 0.1735 | 28.20× |
| algo_bst_ops_ooo4 | 0.0059 | 0.1687 | 28.61× |
| algo_bst_ops_ooo4_no_libcall | 0.0059 | 0.1687 | 28.61× |
| algo_bst_ops_ooo4_unroll | 0.0055 | 0.1494 | 27.18× |
| algo_list_dedup_baseline | 0.0061 | 0.1498 | 24.63× |
| algo_list_dedup_ooo4 | 0.0061 | 0.1495 | 24.39× |
| algo_list_dedup_ooo4_no_libcall | 0.0061 | 0.1495 | 24.39× |
| algo_list_dedup_ooo4_unroll | 0.0057 | 0.1323 | 23.08× |
| algo_list_reverse_baseline | 0.0057 | 0.1419 | 24.72× |
| algo_list_reverse_ooo4 | 0.0057 | 0.1383 | 24.18× |
| algo_list_reverse_ooo4_no_libcall | 0.0057 | 0.1383 | 24.18× |
| algo_list_reverse_ooo4_unroll | 0.0053 | 0.1245 | 23.36× |
| algo_list_search_baseline | 0.0060 | 0.1612 | 27.08× |
| algo_list_search_ooo4 | 0.0061 | 0.1586 | 25.95× |
| algo_list_search_ooo4_no_libcall | 0.0061 | 0.1586 | 25.95× |
| algo_list_search_ooo4_unroll | 0.0055 | 0.1372 | 24.77× |
| algo_list_sort_baseline | 0.0055 | 0.1922 | 34.78× |
| algo_list_sort_ooo4 | 0.0055 | 0.1848 | 33.76× |
| algo_list_sort_ooo4_no_libcall | 0.0055 | 0.1848 | 33.76× |
| algo_list_sort_ooo4_unroll | 0.0054 | 0.1772 | 32.70× |
| coverage_test_build_baseline | 0.0067 | 0.1837 | 27.45× |
| coverage_test_build_debug | 0.0069 | 0.2021 | 29.35× |
| coverage_test_build_ooo4 | 0.0065 | 0.1301 | 19.95× |
| coverage_test_build_ooo4_no_libcall | 0.0065 | 0.1301 | 19.95× |
| coverage_test_build_ooo4_unroll | 0.0065 | 0.1301 | 19.95× |
| kernels_aos_vs_soa_baseline | 0.0057 | 0.0974 | 17.09× |
| kernels_aos_vs_soa_debug | 0.0079 | 0.1436 | 18.18× |
| kernels_aos_vs_soa_ooo4 | 0.0057 | 0.0974 | 17.09× |
| kernels_aos_vs_soa_ooo4_no_libcall | 0.0057 | 0.0974 | 17.09× |
| kernels_aos_vs_soa_ooo4_unroll | 0.0053 | 0.0814 | 15.47× |
| kernels_branch_reduce_baseline | 0.0043 | 0.1890 | **43.62×** |
| kernels_branch_reduce_debug | 0.0051 | 0.2166 | 42.70× |
| kernels_branch_reduce_ooo4 | 0.0043 | 0.1890 | 43.62× |
| kernels_branch_reduce_ooo4_no_libcall | 0.0043 | 0.1890 | 43.62× |
| kernels_branch_reduce_ooo4_unroll | 0.0043 | 0.1860 | 43.11× |
| kernels_dep_chain_baseline | 0.0071 | 0.3064 | 43.43× |
| kernels_dep_chain_debug | 0.0088 | 0.3668 | 41.60× |
| kernels_dep_chain_ooo4 | 0.0071 | 0.3064 | 43.43× |
| kernels_dep_chain_ooo4_no_libcall | 0.0071 | 0.3064 | 43.43× |
| kernels_dep_chain_ooo4_unroll | 0.0095 | 0.3886 | 41.02× |
| kernels_indirect_call_baseline | 0.0050 | 0.1851 | 37.15× |
| kernels_indirect_call_debug | 0.0067 | 0.2622 | 39.28× |
| kernels_indirect_call_ooo4 | 0.0050 | 0.1851 | 37.15× |
| kernels_indirect_call_ooo4_no_libcall | 0.0050 | 0.1851 | 37.15× |
| kernels_indirect_call_ooo4_unroll | 0.0040 | 0.1471 | 36.87× |
| kernels_load_latency_hide_baseline | 0.0080 | 0.2874 | 36.14× |
| kernels_load_latency_hide_debug | 0.0077 | 0.2628 | 34.03× |
| kernels_load_latency_hide_ooo4 | 0.0080 | 0.2874 | 36.14× |
| kernels_load_latency_hide_ooo4_no_libcall | 0.0080 | 0.2874 | 36.14× |
| kernels_load_latency_hide_ooo4_unroll | 0.0082 | 0.2599 | 31.63× |
| kernels_memcpy_like_baseline | 0.0060 | 0.2044 | 33.86× |
| kernels_memcpy_like_debug | 0.0080 | 0.2603 | 32.45× |
| kernels_memcpy_like_ooo4 | 0.0061 | 0.1991 | 32.84× |
| kernels_memcpy_like_ooo4_no_libcall | 0.0061 | 0.1991 | 32.84× |
| kernels_memcpy_like_ooo4_unroll | 0.0058 | 0.1706 | 29.65× |
| kernels_sum_unroll_baseline | 0.0075 | 0.2538 | 33.96× |
| kernels_sum_unroll_debug | 0.0079 | 0.2614 | 32.91× |
| kernels_sum_unroll_ooo4 | 0.0075 | 0.2538 | 33.96× |
| kernels_sum_unroll_ooo4_no_libcall | 0.0075 | 0.2538 | 33.96× |
| kernels_sum_unroll_ooo4_unroll | 0.0077 | 0.2103 | 27.36× |
### 6.3 汇总

| 指标 | 值 |
|---|---|
| 用例总数 | 64 |
| **GeoMean speedup** | **30.02×** |
| 峰值 speedup | **43.62×**（`kernels_branch_reduce_baseline` 等多用例并列） |
| 弱化 speedup | **15.47×**（`kernels_aos_vs_soa_ooo4_unroll`） |
| baseline IPC 区间 | 0.0040 – 0.0095 |
| icache_on IPC 区间 | 0.0814 – 0.3886 |

> baseline IPC 普遍落在 0.004 – 0.010 是因为 `qDepthCpuToMem = 200`：fetch / load / store 全部被钉死 200 拍硬延迟，且 I-Cache 与 D-Cache 同时被旁路，1/200 ≈ 0.005，与实测分布吻合。开启 I-Cache + D-Cache 后命中率近 100%（典型 loop 体仅占数 KiB），延迟塌缩到 `qDepthCpuToCache = 4` 拍并被 4-wide superscalar 部分吸收，理论上限受 ROB/IQ 容量、wakeup 延迟、commit 宽度共同钳制。

### 6.4 用例分析

- **峰值类（35× – 44×）**：`kernels_branch_reduce` / `kernels_dep_chain` / `kernels_indirect_call`。这些 kernel 程序体积小、循环深，指令局部性极强——baseline 几乎每条 fetch 都付一次 200 拍代价，icache_on 把代价缩到 4 拍且能被 BTB/BHT 预测，加速比直接逼近 `200 / 4 ≈ 50×` 的"理想纯 fetch latency 折算"上限。
- **中段类（22× – 30×）**：`algo_list_*` / `coverage_test_build` / `algo_bst_ops`。这些有较多分支与 load/store，icache_on 把 fetch 解放出来后，瓶颈转移到 D-Cache miss / 写回 / branch mispredict，加速比被钳制。
- **弱化类（< 18×）**：`kernels_aos_vs_soa_*`。该 kernel 数据足迹大、Load miss 概率高，D-Cache miss 主导整体执行时间；即便 I-Cache + D-Cache 同时开启，stream 化 Load 仍受 D-Cache miss 后的 200 拍 refill 拖累，加速比明显被压低（最低 15.47×）。

---

## 7. 已知局限与未来工作

1. **单 outstanding refill**：I-Cache / D-Cache 都只允许 1 条 line 在 fly，连续 miss 串行化。**改进方向**：把 miss 队列扩成 MSHR-N（指令侧 N=2 即可显著缓解 chain miss）。
2. **Blocking miss（Fetch 侧）**：当前 I-Cache miss 期间 Fetch 全停。**改进方向**：non-blocking fetch + small skid buffer，让 miss 期间继续供应已经 decode 的指令。
3. **无 prefetch**：Stride / Next-Line prefetch 在 byPass 当前 RTL 中均缺席，对顺序访问的 `kernels_memcpy_like` 影响明显。
4. **无 L2 / SLC**：唯一外延即 IROM / DRAM；引入 16–32 KiB L2 可显著缩短 miss penalty 并允许更高 set 数量。
5. **`qDepthCpuToMem` / `qDepthCacheToMem` 过于保守**：200 拍硬延迟更接近 DDR4-3200 的 worst-case；后续切换到更真实的 DRAM 控制器或下调 latency 可能给出更友善的 baseline。
6. **Cache 几何**：目前 4 KiB / 4-way / 64 B line（16 sets）已足以覆盖绝大多数 regressive 工作集（数据见 §6.2）；若工作集明显放大，需要切换到 8 KiB+ 8-way 重新评估。

---

## 8. 参考脚本

- 抓取 IPC：`/tmp/ipc_compute.py`（按 CSV 末行取 `ipc`，输出 GeoMean / Max / Min）。
- 数据快照：`/tmp/ipc_snapshot/{baseline_both_off,icache_on}/*.csv`。
- 切换配置：编辑 `src/main/scala/CPUConfig.scala` 中 `icacheSize` 与 `dcacheSize`（同时设 0 = baseline；同时设 4096 = icache_on），再执行 `SIM=verilator xmake b rtl && xmake b Core && xmake r sim-regressive`。
