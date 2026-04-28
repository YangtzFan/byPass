# 13 · 三档 Wakeup 时序模型（α / γ / β）

> **目的**：在 PRF 写回前提前广播 pdst，让消费者 IQ 更早 ready，缩短 Load-Use / RAW 距离。

## 1. 三档对比表

| 档 | 广播位置 | 生产者写 PRF 拍 | 消费者读 PRF 拍 | 安全机制 |
|---|---|---|---|---|
| **α** | Refresh 同拍 | N | N+1 | 最保险（Refresh 在写）|
| **γ** | Memory 级 | N+1 | N+2 | per-lane `mshrCaptureFire` 抑制（Load 走 MSHR 那拍不广播）|
| **β** | Execute 级 | N+3 | N+3（同拍写优先 bypass）| **三重门控**（v19 TD-E）|

## 2. αwake（Refresh 同拍）

- **触发**：Refresh 写 PRF 那拍同时广播 pdst；
- **下游**：IQ 同拍命中 → 下一拍 srcReady=1（Reg 翻转）；
- **风险**：无（PRF 数据在写口已就绪，下一拍读 PRF 一定拿到值）。

## 3. γwake（Memory 级）

- **触发**：Memory 阶段 ALU lane / 全覆盖 Load 出结果时广播 pdst；
- **下游**：IQ 命中后下一拍发射，再下一拍读 PRF；此时 PRF 还没写（要 N+1 才写），所以消费者要拿值就靠 Refresh 同拍写优先 bypass；
- **关键抑制**：`mshrCaptureFire(j)=1` 时 lane k=loadLanes(j) 不广播 γ —— 因为 capture lane 的"输出"是 capture 通知而非真数据，广播会让 stale 数据被读。

## 4. βwake（Execute 级）

### 4.1 时序

```
N 拍：Execute 广播 pdst（IQ 同拍命中）
N+1：消费者发射（src ready=1）
N+2：消费者 RR
N+3：消费者 EX；同拍生产者 Refresh 写 PRF
N+3：消费者读 PRF（靠同拍写优先 bypass 拿值）
```

距离压缩到 **3 拍**（vs αwake 的 5 拍）。

### 4.2 三重门控（`MyCPU.scala:545-572`）—— v19 TD-E 不变量 I-C

```scala
val anyLoadInExBundle = (0 until executeWidth).map { k =>
  uRRExDff.out.bits.validMask(k) && uRRExDff.out.bits.lanes(k).type_decode_together(4)  // 4 = Load
}.reduce(_ || _)

val exAdvance = uRRExDff.out.fire && uMemory.in.ready && !anyLoadInExBundle
```

| 门控 | 作用 |
|---|---|
| `RRExDff.out.fire` | 确保生产者本拍真推进 |
| `Memory.in.ready` | 确保当前 ExMemDff→Memory 通路无反压 |
| `!anyLoadInExBundle` | 消除"同 bundle Load 触发 memStall 把 ALU 生产者一并卡在 ExMemDff"路径，从源头保证 N+3 抵达 Refresh |

### 4.3 βwake 启用范围（v20 P0+P1 安全版）

```scala
val betaEnable = (k.U === 0.U) || (k.U === 2.U) || (k.U === 3.U)
// lane0 + lane2 + lane3 启用；lane1 仍禁用（不变量 I-D，留 P1 后续验证）
```

- **lane2/3 是 ALU-only**，本身永不可能是 Load；但仍受第 3 重门控保护（bundle 原子推进，同 bundle 任意 Load 触发 memStall 时 lane2/3 ALU 生产者也会被卡）；
- **lane1 仍禁用**：lane1 ALU 生产者→消费者 RAW 时序需独立波形验证，留 v22+ P1。

## 5. βwake 收益（v20 实测）

| TC | v18 | v19.3 | v20 |
|---|---|---|---|
| `kernels_dep_chain_ooo4_unroll` | — | 1.396 | **2.063** (+47.8% vs v19.3) |
| `kernels_load_latency_hide_baseline` | 1.544 | 1.515 | **1.727** (+11.8% vs v18) |
| `kernels_memcpy_like_ooo4_unroll` | 1.248 | 1.220 | **1.368** (+9.6% vs v18) |

## 6. 关键不变量

1. **βwake 三重门控不可拆**（I-C）：任一缺失即破坏 N+3 PRF 写时序假设；
2. **γwake mshrCaptureFire 抑制**：capture lane 不广播 γ；
3. **αwake 是基线**：永远启用，所有时序假设以 αwake 为基。

## 7. 调试切入点

- 怀疑 βwake 取错值：dump βwake 广播拍 + N+3 拍 PRF 写口 + 消费者读 raddr；
- 怀疑 γwake 取错值：检查 mshrCaptureFire 是否在 capture 拍真为 1；
- IPC 不达预期：先关 βwake（注释 line 560-568）跑 sim-regressive，比较 IPC 差。
