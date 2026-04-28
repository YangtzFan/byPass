# 01 · 前端子系统（Fetch + BHT + BTB + FetchBuffer）

> **角色**：每拍向后端"喂"4 条对齐的指令，并对分支/JALR 做预测；通过 FetchBuffer 解耦 Fetch 与 Decode 节奏差，吸收前端抖动。

## 1. 涉及源文件

| 文件 | 角色 |
|---|---|
| `dataLane/Fetch.scala` | 取指主体，4-wide IROM 读 + 预测合并 + ≥3 截断 |
| `device/BHT.scala` | 64 项 Branch History Table（2-bit Gray 计数）|
| `device/BTB.scala` | 32 项 Branch Target Buffer（含 JALR 间接目标）|
| `dataLane/FetchBuffer.scala` | 32 项环形缓冲，前端反压队列 |

## 2. Fetch 数据通路

```
PC → IROM 读 128 bit (4×32) → 4 槽位（slot[0..3]）
        │
        ├─ PC[3:2] = startSlot：startSlot 之前槽位 valid=0（前对齐）
        ├─ 每槽查 BHT（PC[7:2] 索引）+ BTB
        └─ 命中 taken → 该槽起 ≥3 槽位截断 valid（v15 不变量）
                        |  避免错误路径污染 ROB
                        ↓
                  enqueue → FetchBuffer
```

- **4 路对齐取指**：IROM 一拍出 128 bit，按 PC[3:2] 决定起始 slot；起始之前的槽位标 valid=0。
- **≥3 预测截断**（v15 不变量）：当某槽预测 taken 时，本拍该槽之后 ≥3 个槽位 valid=0；避免连续两次 redirect 之间出现错误 taker 的污染指令。

## 3. BHT —— `device/BHT.scala`

- 64 项，2-bit Gray 计数（00→01→11→10）；每项一个 `taken` 历史；
- 索引：`PC[7:2]`；
- 更新口仅在 **Execute 阶段**（lane0 计算实际方向后）；Memory.redirect 有效时**抑制更新**（防错误路径污染 BHT 状态）。

## 4. BTB —— `device/BTB.scala`

- 32 项，主要给 JALR 间接目标预测；
- 索引：PC 低位；
- 命中标记 `btbHit`，目标地址 `btbTarget`；Execute 阶段写回更新。

## 5. FetchBuffer —— `dataLane/FetchBuffer.scala`

- 环形缓冲 32 项（`fetchBufferEntries=32`，v19 升级）；
- 每拍 enqueue ≤4、dequeue ≤4；
- redirect / flush：整体清空 + 头尾指针归零；
- Decode 端口为后端接口，与 Decode 之间无 Dff（直连），由 FetchBuffer 自身解耦。

## 6. 关键不变量

1. **v15**：FetchBuffer 内 ≥3 预测分支截断，禁止错误路径连环污染；
2. BHT/BTB 更新窗口在 Execute；Memory.redirect=1 时抑制更新；
3. PC 重定向源优先级：Memory.redirect（mispredict）> Execute Branch verify > 取指顺序。

## 7. 调试切入点

- 怀疑前端阻塞：看 `FetchBuffer.head/tail` 与 IROM `arvalid/arready`；
- 怀疑预测错误：看 BHT 状态 + Execute 阶段 `lane0MispredictEarly` 触发频率；
- 怀疑取指 PC 漂移：dump `Fetch.pcReg` 与 `MyCPU.redirect` 信号。
