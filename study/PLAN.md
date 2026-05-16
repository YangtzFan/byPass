# TASK v21 —— 代码清理 ✅ 已闭环（毕设交付版）

> **里程碑状态**：✅ v21 完成。本项目作为《一种乱序超标量处理器芯片全流程设计与应用》本科毕业设计已达交付水准。
> **历史任务书备份**：v21wip → `TASK.md.v21wipbak`、v20 → `TASK.md.v20bak`、v19.3 → `TASK.md.v19_3bak`、…

---

## 0. 交付状态总览

| 项 | 状态 |
|---|---|
| **微架构** | 4 发射乱序，BPU+ROB+IQ+SB+AXISB+MSHR+BCT+βwake |
| **回归（功能正确性）** | sim-basic **39/39 PASS** + sim-regressive **64/64 PASS** |
| **IPC（性能指标）** | 多用例超 v18 baseline；峰值 `kernels_dep_chain_ooo4_unroll = 2.063` |
| **文档** | PLAN/STUDY/TASK/BUG/LOG 五件套齐全；历次任务书备份完整 |
| **代码清晰度** | v21 完成 stale 注释/死 scaffolding 清理；imports/wires 调查通过 |

---

## 1. v21 三个 Batch 实际执行情况

### Batch 1 ✅ 已落地（stale 注释 + 死 scaffolding）

| 文件 | 改动摘要 |
|---|---|
| `CPUConfig.scala:55-61` | `iqIdxWidth` 注释错误位宽 "4 位" → "6 位"；半区间表述 "≤ 16" → "≤ 48"；备注由 "Phase E：32→48 缓解 IQ 满阻塞" 改为 "实测最优：32 易满阻塞、64 时序退化" |
| `dataLane/IssueQueue.scala:130-186` | 删除"阶段 α.1（已暂时回滚）"整段过渡说明；折叠 `wk1ext/wk1`、`hitExt1/hit1` 死中转变量（fastWk Reg 路径已被前序里程碑移除）；section 标题"阶段 α.1 / 阶段 1"前缀去除 |
| `dataLane/IssueQueue.scala:314` | 删除"阶段 α.1（已回滚）：fast wakeup 导出逻辑已移除"占位注释 |
| `MyCPU.scala:151,200` | "阶段 1" 前缀去除（实际功能已稳定融入主线）|
| `dataLane/DiffPayloads.scala:56,348` | 同上 |
| `dataLane/Rename.scala:247` | 同上 |

**验收**：
```
sim-basic   : 39/39 PASS
sim-regressive: 64/64 PASS
IPC diff vs v20 baseline: 0 differences (120 TC 完全一致)
```

### Batch 2 ✅ 调查通过（无可执行清理项）

- **imports 调查**：全 36 个 `.scala` 文件、~80 行 `import` 语句逐项核查，全部被使用；
- **Wire 调查**：扫到的 ~50 个 `Wire(_)` 声明均有后续 `:=` 写入和读取下游使用，无明显死信号；
- **结论**：代码已经过多轮迭代清理（v14/v15/v17/v18/v19/v20），重构压力已被前序里程碑消化；本批无可改之处。

### Batch 3 ✅ 主动收敛（不做）

候选改写：
- 级联 `PriorityEncoder` 链 → `for` 循环（IssueQueue.scala:114-120）
- `val tmp = expr; ...tmp...` 内联化
- 重复 `validMask(k) && lanes(k).type_decode_together(4)` 抽 helper

**收敛理由**：
1. 每项改写需 SV diff + 全量 IPC 回归保护（每次 ~10 分钟）；
2. 单点收益 < 0.1% 可读性提升；
3. 对已通过完整回归的稳定项目引入此类风险不划算；
4. 毕设交付优先级：**功能/性能/文档** > 代码美观。

---

## 2. v21 验收 IPC 表（与 v20 baseline 完全一致）

```
algo_array_ops_baseline                       1.114628
algo_array_ops_ooo4_unroll                    0.855328
kernels_load_latency_hide_baseline            1.726602
kernels_load_latency_hide_ooo4_unroll         1.805313
kernels_memcpy_like_baseline                  1.273312
kernels_memcpy_like_ooo4_unroll               1.367963
kernels_dep_chain_ooo4_unroll                 2.063122  ← 峰值
kernels_sum_unroll_ooo4_unroll                1.471502
…（120 项与 v20 baseline diff = 0）
```

校准命令：
```bash
cd /nfs/home/zhanghang/Documents/difftest
for f in build/sim-data/*.csv; do
  name=$(basename "$f" .csv); ipc=$(tail -1 "$f" | awk -F',' '{print $8}')
  printf "%-55s ipc=%s\n" "$name" "$ipc"
done | sort > /tmp/ipc_v21.txt
diff /tmp/ipc_v20_baseline.txt /tmp/ipc_v21.txt   # 0 diff ✅
```

---

## 3. 不变量（毕设最终版本必守清单）

继承 v14 → v20 全部不变量，本次清理**未触碰任何不变量逻辑**：

1. **v14**：BCT ReadyTable 同拍 busyVen 旁路；
2. **v15**：FetchBuffer ≥3 预测分支截断；
3. **TD-D**：`mshrComplete` IO Vec(2) per-slot 独立 ack；MyCPU 双 j 仲裁；
4. **v18**：difftest emu.lua / xmake.lua / main.lua 三件套；
5. **TD-E (v19.3) + stale-base 修复**：
   - I-A：Memory bundle 原子性；
   - I-B：双 capture all-or-none；
   - I-C：βwake 四重门控不可拆（exAdvance 三重 + `!downstreamLoadStall`）；
6. **当前实际版本**：βwake 范围 = lane2 + lane3，lane0 / lane1 全部禁用（lane0 因 Full lane 含 Load 风险保守关闭，lane1 未独立验证）。
7. **v22 (Phase A.2)**：2-store/拍 commit 端到端升级 —— SB/AXISQ/sqEnq/debug_commit 全 Vec(K=2)；ROB `storeMustBeLane0Only` 放宽为"store 仅在 storeLanes={0,1}"+ per-lane `commitBlocked: Vec(K)` 反压。

---

## 4. 候选未来工作（**毕设之外，不做**）

如有人后续想继续优化此项目（非毕设范围），按 IPC 杠杆排序：

| 优先 | 任务 | 备注 |
|---|---|---|
| P1 | lane0 / lane1 βwake 启用 | 补 `algo_array_ops_ooo4_unroll` 剩余 gap |
| P2 | 精确 lookahead 门控 | 替换粗粒度 `!anyLoadInExBundle` |
| P3 | Bug B 根因深挖 | algo_list_reverse_ooo4_unroll 在 βwake 全禁场景的异常 |
| P4 | TD-F 双发 Branch | 解除 doubleBrStall |
| P5 | BTB/RAS 升级 | indirect_call IPC 仅 0.41 |
| P6 | AXI 写多 outstanding | Phase A.2 后 2-store/拍 commit 已就位，但 AXI 写仍单 outstanding，store-heavy 用例提升受限 |
| P7 | lane2/3 全功能 | 远期 / TD-G |

---

## 5. 命令速查（毕设演示用）

```bash
cd /nfs/home/zhanghang/Documents/difftest

# 全量编译
SIM=verilator xmake b rtl
SIM=verilator xmake b Core

# 全量回归（200 并发 JOBS）
SIM=verilator xmake r sim-basic        # 39 例 ~30s
SIM=verilator xmake r sim-regressive   # 64 例 ~5min

# 单测 + 落盘日志：build/sim-log/<tc>.log + build/sim-data/<sweep>/<tc>.csv
SIM=verilator TC=<tc_name> xmake r sim-single
```

---

## 6. 上手必读（移交他人继续维护时）

1. `scripts/STUDY.md` —— 当前架构与 IPC 快照（318 行精简版）；
2. 本文件（`TASK.md` v21 闭环）；
3. `scripts/PLAN.md` —— 里程碑路线（v21 ✅ 已交付）；
4. `scripts/BUG.md` —— Bug A 已修 + Bug B 未修但已绕开（候选 v22+ 工作）；
5. `scripts/LOG.md` —— v14 → v21 完整时间线；
6. `scripts/wave-debug/SKILL.md` —— **任何波形调试前必读**。

---

## 7. 毕设交付确认

✅ 设计：4 发射乱序处理器，全流程已实现；
✅ 验证：103 个测试用例全 PASS（39 sim-basic + 64 sim-regressive）；
✅ 性能：峰值 IPC 2.063，多个 baseline 用例 ≥ 1.5；
✅ 文档：五件套齐全 + 全部历史任务书备份；
✅ 代码清晰度：经 v21 stale 注释清理，"代码与文档表述一致"。

**本项目作为本科毕业设计已达可交付状态**。

---

## 8. v21 后续 · 文档重构（study/ 目录）

应用户要求，在 v21 代码清理后**重组研究文档**，便于他人快速理解 Chisel 源码：

### 改动
- 新建 `study/` 目录；
- `scripts/STUDY.md` → `study/SYSTEM_TOP.md`（系统总览 + 模块文档导航表）；
- 按功能点拆分为 14 个独立模块文档：

| 文件 | 内容 |
|---|---|
| `study/01_frontend.md` | Fetch + BHT + BTB + FetchBuffer |
| `study/02_rename.md` | Rename + RAT + FreeList + ReadyTable |
| `study/03_dispatch.md` | Dispatch + ROB tail / SB 槽分配 + IQ 入队 |
| `study/04_issue_queue.md` | IssueQueue（48 项）+ wakeup 接收 |
| `study/05_issue_arbitration.md` | Issue 多发射仲裁（pairRaw / doubleBrStall）|
| `study/06_readreg_prf.md` | ReadReg + PRF + 同拍写优先 bypass |
| `study/07_execute.md` | Execute 双 ALU + EX-in-EX 旁路 + 分支验证 |
| `study/08_memory_mshr.md` | Memory + 双 capture MSHR + AR 仲裁 |
| `study/09_refresh.md` | Refresh + per-lane 写口仲裁 + ROB done |
| `study/10_rob.md` | ROB（128 项）+ 提交规则 |
| `study/11_branch_checkpoint.md` | BCT + 投机回滚路径 |
| `study/12_storage_subsystem.md` | StoreBuffer / AXIStoreQueue / DRAM / IROM |
| `study/13_wakeup_model.md` | α / β / γ 三档 wakeup + 四重门控 |
| `study/14_invariants_and_hazards.md` | 不变量清单 + Hazard 规则速查 |

每个模块文档统一结构：**源文件 → 接口 / 数据通路 → 关键 Chisel 实现 → 不变量 → 调试切入点**。

### 配套更新
- `README.md`：研究入口指向 `study/SYSTEM_TOP.md`；
- `scripts/STUDY.md.v19_3bak` 保留（历史归档不动）；
- 不影响 RTL，无需回归测试。

### 阅读顺序建议
1. `study/SYSTEM_TOP.md`（含 §一流水线全景图）；
2. 按上表顺序逐模块阅读，对照源码；
3. 任何不变量疑问查 `study/14_invariants_and_hazards.md`。
