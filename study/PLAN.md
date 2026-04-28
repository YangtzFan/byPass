# 里程碑路线图（PLAN）

> 与 `STUDY.md`（当前架构 + IPC 快照）、`TASK.md`（当前迭代任务）、`LOG.md`（时间序施工日志）、`BUG.md`（已发现 BUG 与修复）配套使用。
> 本文件只记录"里程碑级"路线，不记录细节。

---

## 📍 当前里程碑：v21 代码清理 ✅ 已完成

- **状态**：Batch 1 落地（stale 注释 + 死 scaffolding）；Batch 2 调查无可执行项；Batch 3 主动收敛。
- **回归**：sim-basic 39/39 + sim-regressive 70/70 PASS。
- **IPC**：与 v20 baseline `diff` 完全一致（120 TC 零差）。
- **细节**：见 `LOG.md` v21 章节、`TASK.md` v21、`BUG.md` 无新增。

---

## 📍 上一个里程碑：v20 IPC 回收 ✅ 已完成

- **状态**：sim-basic 39/39 PASS + sim-regressive 70/70 PASS（双绿）。
- **核心改动**：lane2/3 βwake 启用（保留三重门控）。
- **关键 IPC 收益**（vs v18 baseline）：
  - `kernels_load_latency_hide_baseline` +11.8%
  - `kernels_memcpy_like_baseline` +10.7%
  - `kernels_memcpy_like_ooo4_unroll` +9.6%
  - `kernels_load_latency_hide_ooo4_unroll` +2.5%
  - `kernels_dep_chain_ooo4_unroll` 创新高 2.063 IPC（v19.3 起跑点 1.396，+47.8%）。
- **细节**：见 `LOG.md` v20 章节、`STUDY.md` §11.4、`TASK.md` v20。

---

## 历史里程碑速览

| 里程碑 | 主题 | 状态 |
|---|---|---|
| TD-A/B/C | 多发射底盘 + lane 能力扩展 | ✅ |
| v14 | BCT ReadyTable 同拍旁路修复 | ✅ |
| v15 | FetchBuffer ≥3 预测分支截断 | ✅ |
| v17 | indirect_call_debug 跑飞实证 | ✅ |
| v18 | difftest 内存模型修复（emu.lua + xmake.lua + main.lua）| ✅ |
| TD-D | mshrComplete Vec(2) per-slot 独立 ack | ✅ |
| TD-E (v19.3) | dual-Load 启用 + βwake 三重门控 | ✅ |
| v20 | lane2/3 βwake + IPC 回收 | ✅ |
| **v21** | **代码清理（stale 注释 + 死 scaffolding）** | **✅ NEW** |

---

## 下个里程碑候选（v21+）

按 IPC 杠杆与风险排序，详见 `STUDY.md` §十二：

1. **P1**：lane1 βwake 启用（前置：P3 Bug B 深挖）；
2. **P2**：精确 lookahead 替换 `!anyLoadInExBundle`（含 Load 但不实际 stall 的 bundle 也放 βwake）；
3. **P3**：Bug B 根因深挖（algo_list_reverse_ooo4_unroll 在 βwake 全禁场景）；
4. **P4**：TD-F 双发 Branch（解除 doubleBrStall）；
5. **P5**：BTB / RAS 升级（针对 indirect_call IPC=0.41）；
6. **P6**：lane2/3 全功能（远期 / TD-G）。

---

## 上手必读（按顺序）

1. `STUDY.md` —— 当前架构与 IPC 快照（已精简，去除全部历史）；
2. `TASK.md` —— 当前迭代任务（v20 闭环，下次会话替换为 v21）；
3. 本文件 `PLAN.md` —— 里程碑路线；
4. `BUG.md` —— 已发现 BUG 与修复；
5. `LOG.md` —— 时间序施工日志；
6. `scripts/wave-debug/SKILL.md` —— **任何波形调试前必读**。
