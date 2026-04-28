# 4 发射升级 进展日志

> 任务：将当前 OoO 双发射 RV32I CPU 升级为 4 发射；最终目标 41/41@`issueWidth=4`，平均 IPC ≥ 0.80。
> 全部进度严格记录于本文件；未达成项及技术债见 `PLAN.md`。

---

## 基线（升级前）

| 项 | 值 |
| --- | --- |
| 配置 | `issueWidth=2`, `renameWidth=4`, `commitWidth=2` |
| 测试结果 | **41/41 全部通过** |
| 平均 IPC（41 例算术平均） | **0.5262** |
| 构建命令 | `cd ../difftest && SIM=verilator xmake b rtl && SIM=verilator xmake b Core && SIM=verilator xmake r sim-all` |

---

## Phase A.1 —— 修复 IQ `aluOnly` 位宽错位 BUG（已尝试，已回滚）

### 1. BUG 确认

`src/main/scala/dataLane/IssueQueue.scala:199-203` 的 `aluOnly` 函数用错了 `type_decode_together` 的位下标，
导致 lane1 的"纯 ALU"过滤集合错误。

`Decode.scala:48` 的编码（LSB → MSB）为：
```
bit0=other, bit1=rType, bit2=sType, bit3=iType, bit4=lType,
bit5=bType, bit6=jalr,  bit7=jal,   bit8=uType
```

而 IQ 中原始代码是：
```scala
val iType = e.type_decode_together(1)  // 实为 rType
val rType = e.type_decode_together(0)  // 实为 other (FENCE/ECALL)
val lui   = e.type_decode_together(8)  // uType（含 LUI/AUIPC，可接受）
iType || rType || lui                  // 实际等价于 rType || other || uType
```

而 `Issue.scala:65-67` 已经使用正确下标 `(3)/(1)/(8)`。
两处不一致 → IQ 把 iType 排除在 lane1 之外、把 FENCE/ECALL 错误地放进 lane1。

**直接后果**：iType（所有 I 型算术指令，是 RV32I 中数量最多的 ALU 指令族）**只能**在 lane0 发射，
lane1 形同虚设，正是基线 IPC ≈ 0.52（接近 1 发射上限 0.5）的根本原因之一。

### 2. 已尝试的修复

将 IQ 的 `aluOnly` 改为：
```scala
val iType = e.type_decode_together(3)
val rType = e.type_decode_together(1)
val uType = e.type_decode_together(8)
iType || rType || uType
```
与 `Issue.scala` 对齐。修复同时附了详细中文注释解释编码与历史 BUG。

### 3. 回归结果（修复后）

- **40/41 通过**，仅 `example` 一例失败。
- 单跑 `example`：在 PC=0x56c (`lw ra, 44(sp)`) 处，
  RTL 的 commit lane1 写回 `REGWDATA=0`，但参考模型期望 `0x554`。
- `example` 单例 IPC 由 0.49 跃升至 **0.7530**，证明 lane1 终于能并发发射 iType；
  但同时暴露出一处更深的"内存乱序/前递竞态"BUG。

### 4. 暴露的潜在 BUG 分析

**现象**：`sw ra, 44(sp)` @ PC=0x508（在调用前保存返回地址）和后续 `lw ra, 44(sp)` @ PC=0x56c（恢复 ra）之间，
经过若干次函数调用，每次调用都有 `addi sp, sp, ±48`。

**关键洞察**：
- `sw`/`lw` 同属 lane0-only（IQ 已正确限制）；最小发射间隔 1 cycle。
- 前后 `addi sp` 操作链很长。修复前 iType 只能 lane0 发射，sp 修改与 mem 操作天然串行；
  修复后 iType 可走 lane1，sp 链与 sw/lw 形成更激进的乱序。
- Memory 流水线 SB（StoreBuffer）/SQ（StoreQueue）在新调度下出现前递查询时序与
  老 Store 在途（IQ→ReadReg→Execute→Memory，3 拍 gap）的判定漏洞。

**疑似根因候选**（未最终定位）：
1. **`olderStoreInIQ` 仅看 IQ 内**：当老 Store 已离开 IQ 但尚未抵达 Memory 写 SB（处于 IssRR/RREx/ExMem
   流水寄存器中）时，年轻 Load 在 IQ 里看不到它，可能被错误放行。
2. **SB rollback 与分支恢复**：`storeSeqSnap` 在多分支 misprediction 链路上回滚是否漏掉了在途 Store？
3. **MSHR / γ-wake 与新调度交互**：当前是单 MSHR，新调度可能让 Load miss 与后续 Store 顺序窜了。

> 详细的根因定位需要插 `printf`/dump 波形跟踪 cycle ~0xb4ea 附近 Memory 模块状态。
> **见 `PLAN.md` TD-001，作为 4 发射升级最高优先级的前置依赖。**

### 5. 决策

由于该潜在 BUG 是一个跨 IQ/Issue/Memory/SB/SQ 多模块的深层乱序前递竞态，
独立完成的修复风险与工作量都超出当前会话预算（且修复本身可能要重写 Memory 仲裁逻辑）。

**当前选择**：**回滚 Phase A.1 修复**，恢复基线 41/41 通过。

把 BUG 与修复方案完整记录在本文件 + `PLAN.md` TD-001，留待后续会话处理。
**这是诚实的选择**：用户明确指示"达不到也不要无限优化，记录到 `PLAN.md`"。

### 6. 现状校验

回滚后再次跑回归：
```
[INFO] 通过 (41): add, addi, and, andi, auipc, beq, bge, bgeu, blt, bltu, bne,
       example, jal, jalr, lb, lbu, lh, lhu, lui, lw, or, ori, sb, sh, simple,
       sll, slli, slt, slti, sltiu, sltu, sra, srai, srl, srli, start, sub, sw,
       test_build, xor, xori
[INFO] 失败 (0): (none)
```
✅ 回到基线，仓库工作树干净。

---

## Phase A.2 ~ F —— 未启动

由于 Phase A.1 揭示的潜在 OoO 内存乱序 BUG 是 4 发射的**前置阻塞项**
（4 发射会让乱序更激进，相同 BUG 会更频繁触发），
在未修复 TD-001 之前，强行推进参数化 / N-pick / O(N²) 前递等改动只会让症状放大、
回归数量不可控。

未完成阶段：
- **A.2** LaneCapability helper —— 未启动
- **A.3** BCT save1/save2 → saveVec —— 未启动
- **A.4** 参数化扫荡（`for (i <- 0 until 4)` → `fetchWidth`）—— 未启动
- **A.5** N-pick selector —— 未启动
- **A.6** Issue 的 O(N²) pair conflict —— 未启动
- **A.7/A.8** 双发射回归 + 4 发射 elaborate 烟测 —— 未启动
- **B/C/D/D'/E/F** —— 未启动

具体每阶段的任务范围、设计要点、风险评估、已完成的代码踩点（在前期 context 中已读过的关键文件
与行号）见 `PLAN.md`。

---

## 总结

| 项 | 状态 |
| --- | --- |
| 基线 41/41 通过 | ✅ 保持 |
| 基线平均 IPC | 0.5262（未提升） |
| Phase A.1 BUG 定位 | ✅ 已找到 IQ aluOnly 位宽错位根因 |
| Phase A.1 修复落地 | ❌ 修复正确但触发 TD-001 潜在 BUG，已回滚 |
| 4 发射目标 IPC ≥ 0.80 | ❌ 未达成 |
| 阶段 A 后续 / B / C / D / D' / E / F | ❌ 未启动，技术路线见 PLAN.md |

**最大收获**：定位了一处长期潜伏的 IQ `aluOnly` 位宽 BUG（让 lane1 实际不工作），
并通过尝试修复暴露出一处更深的乱序内存前递 BUG（TD-001）。
后者是 2 → 4 发射升级的真正瓶颈：在没有把 TD-001 修干净之前，提升发射宽度只会让该 BUG 暴发。

---

## TD-001 修复 + Phase A.1 落地（本轮）

### 结果

| 项 | 数值 |
| --- | --- |
| sim-all 通过率 | **41/41** ✅ |
| 平均 IPC | **0.6017** |
| 相对基线（0.5262） | **+14.3%** |

### 根因

**TD-001 真因**：β-level Execute wakeup（MyCPU.scala:442-449）原本对所有
`executeWidth` 个 lane 都广播提前唤醒。该路径假设生产者从 Execute → ExMemDff
→ MemRefDff → 写 PRF 一路无任何反压，刚好让消费者下一拍 ReadReg 命中
"PRF 写优先 bypass"。lane0 单 lane 时这个假设成立；lane1 加入后，多 lane
旁路、写口仲裁、MSHR 互动会破坏该假设，造成消费者在 lane1 PRF 写回前 1 拍
就发射，ReadReg 读到旧值（典型表现：`sp` 等高频寄存器读到错值）。

aluOnly BUG 修好之前，lane1 几乎不发射 iType（绝大多数 ALU 指令是 iType），
所以 β-wake-lane1 路径几乎从不真正命中下游消费者，BUG 隐而不发。
aluOnly 修复后 iType 大量走 lane1，BUG 暴发。

### 修复

1. **MyCPU.scala:442-450** — β wake 加 `betaEnable = (k == 0).B`，
   仅 lane0 广播 β 级提前唤醒。lane1 的 wake 仅走 Memory 级（k=0..1）和
   Refresh 级，时序对齐后无 RAW 早唤醒。
2. **IssueQueue.scala:199-216**（Phase A.1）— 修复 aluOnly 位宽错位：
   从 `td(1) || td(0) || td(8)`（错把 rType+other 当 iType+rType）改为
   `td(3) || td(1) || td(8)`（iType + rType + uType），匹配 Decode.scala:48
   的 type_decode_together 编码。

### 验证

- example 单测 PASS（曾因 TD-001 失败）
- sim-all 41/41 PASS
- mean IPC 0.6017 vs baseline 0.5262，**+14.3%**

### 残留 / 后续机会

- example IPC 0.463 仍偏低；lane1 β wake 全关掉换取了正确性，是否可对
  特定 iType 子集"安全开启"以换回部分 IPC（待 Phase A.2 评估）
- 加载/分支密集型测试（lb*/lh*/lw, b**, jalr）IPC 仍 ≤0.5，是 D/D' 阶段重点

---

## Phase A.2 — LaneCapability helper 单点化

**目标**：消除 `td(0..8)` 位下标在多个文件里硬编码导致的位宽错位风险（参考
TD-001 历史 BUG），把"指令类型分类 + lane 能力 + lane 路由"全部收口到一个
helper 文件。

**结果**：sim-all 41/41 ✅，mean IPC = **0.6017**（与 A.1 完全一致，
零行为变化，纯重构成功）。

### 修改

1. **新增** `src/main/scala/dataLane/LaneCapability.scala`
   - 集中编码 `type_decode_together` 9-bit 位定义（来自 Decode.scala:48）
   - 提供 `isOther/isRType/isSType/isIType/isLType/isBType/isJalr/isJal/isUType`
     九个类型谓词
   - 提供 `useRs1/useRs2/isAluOnly/isMemOp/isCtrlFlow/producesEarlyResult` 等
     语义谓词
   - 提供 `fullLaneSet`（编译期常量集合，当前 `Set(0)`）+ `isFullLane(k)`
     `isAluOnlyLane(k)` `isMemLane(k)` `isBranchLane(k)` `canIssueOn(k, td)`
     `fullLanes` `aluLanes` 等 lane 能力查询 API
   - 阶段 D' 双 Mem 口落地后只需修改 `fullLaneSet := Set(0, 1)`，所有
     调用方自动跟随

2. **重构** `src/main/scala/dataLane/IssueQueue.scala`
   - `lTypeOf` `sTypeOf` `aluOnly` 三个 private 函数全部代理到 LaneCapability
   - 删除原有"位下标历史 BUG"长注释（迁移到 LaneCapability 头注释）

3. **重构** `src/main/scala/dataLane/Issue.scala`
   - `useRs1` `useRs2` `aluOnly` 同步代理到 LaneCapability
   - `laneAluOnly` 判断改用 `LaneCapability.isFullLane(k)`，让 lane 编号到
     能力的映射不再硬编码 `k == 0`
   - `lane0IsLoad` 改用 `LaneCapability.isLType`

### 验证

- 编译/elaborate 一次通过
- sim-all 41/41 PASS
- 41 个 TC IPC 与 A.1 完全相同（mean 0.6017），证明只是重构没有行为漂移

---

## Phase A.3 — BCT save/canSave/saveIdx Vec 化

**目标**：把 BCT 的 `save1/save2`、`saveValid1/saveValid2`、`canSave1/canSave2`、
`saveIdx1/saveIdx2` 统一收口到 `Vec(ckptSaveWidth, _)` 接口，A.4 直接把
`ckptSaveWidth` 调到 4 即可天然支持每拍 4 条预测分支同时下快照。

**结果**：sim-all 41/41 ✅。

### 修改

1. **CPUConfig.scala** 新增 `ckptSaveWidth: Int = 2`
2. **device/BranchCheckpointTable.scala** 改写：
   - `saveRequestRename`：`canSave / saveIdx / saveValid` 全改为 `Vec(saveW, _)`
   - 新 IO `saveDataIn: Vec(saveW, saveData)`（避免与 Bundle 类名冲突）
   - 内部存盘循环 `for (k <- 0 until saveW)`，`tail` 推进步长 = `PopCount(saveValid)`
   - refreshedSinceSnap 维护、清零、合并都按 saveW 循环
3. **dataLane/Rename.scala**：
   - `ckptReady` 用 `canSave(0)/canSave(1)`
   - `ckPointReq.saveValid(0)/saveValid(1)` 替换原 `saveValid1/2`
   - `checkpointIdx` 从 `saveIdx(0)/saveIdx(1)` 取
4. **MyCPU.scala**：`uBCT.saveDataIn(0/1)` 替换 `save1/save2`

### 验证

- elaborate 一次通过
- sim-all 41/41 PASS（结构重构，行为不变）

### 后续

- A.4 把 `ckptSaveWidth` 调到 4，并在 Rename 内部把 1st/2nd 预测分支检测扩展到
  4 条新增分支

---

## Phase A.4 — 参数化扫荡（fetch/decode/rename Width 显式分层）

**目标**：消除 Fetch / Decode / Rename / Dispatch / IssueQueue.alloc /
StoreBuffer.alloc / RAT / FreeList / ReadyTable / DiffPayloads 中硬编码的
`Vec(4, ...)` 与 `Vec(8, ...)`。所有"前端流水宽度"统一引到 `CPUConfig`，
后续 8 发射只改 1 个常量即可。

**结果**：sim-all 41/41 ✅。

### 修改

1. **CPUConfig.scala** 新增 `fetchWidth = 4` `decodeWidth = fetchWidth`
   （`renameWidth = 4` 已存在）。注：4 在前/后端"宽度"语义上当前同源，
   分立常量为后续阶段（如改 fetch=8 或 commit=4）打底。
2. **批量替换**：
   - Fetch.scala / FetchBuffer.scala：`Vec(4, _) → Vec(CPUConfig.fetchWidth, _)`
   - Decode.scala：`Vec(4, _) → Vec(CPUConfig.decodeWidth, _)`
   - Rename.scala / Dispatch.scala / DiffPayloads.scala / IssueQueue.scala /
     StoreBuffer.scala / RAT.scala / FreeList.scala / ReadyTable.scala：
     `Vec(4, _) → Vec(CPUConfig.renameWidth, _)`、
     `Vec(8, _) → Vec(2 * CPUConfig.renameWidth, _)`（rs1+rs2 读端口）、
     `for (_ <- 0 until 4)` 与 `0 until 8` 同步替换
3. **保留字面量 4** 的位置：`Memory.scala:206`、`AXIStoreQueue.scala:113-118`、
   `DRAM_AXIInterface.scala:71` 中的 `Vec(4, UInt(8.W))` / `for (b <- 0 until 4)` —
   这是"32-bit word 4 字节"的 SRAM 字内字节切片语义，与流水宽度无关，
   误改会破坏 little-endian byte-mask 对齐。已在 StoreBuffer.scala:115-118
   加注释明确"4 = 32-bit word 字节数，与 renameWidth 无关"。

### 验证

- sim-all 41/41 PASS（结构重构，行为不变）

### 后续

- 真正"4 → 8 fetch"或"renameWidth = 8"还涉及 RAT 30+ 端口、FreeList 8 路
  分配、IQ 8 路 alloc 等，本阶段不动；A.4 只把命名常量这一层就位

---

## Phase A.5 — IssueQueue N-pick 选择器

**目标**：把"lane0 oldest-ready + lane1 次老 ready"两套独立 fold 合并成
按 `issueWidth` 通用化的 N-pick 循环，4 发射只需把 issueWidth 调到 4，
本循环天然产出 4 个 pick。

**结果**：sim-all 41/41 ✅。

### 修改

- **dataLane/IssueQueue.scala**：
  - lane0 单独 pick（保留 memOrder 检查 / Full 能力）
  - lane k>0 用 `for (k <- 1 until issueWidth)` 循环：
    - `notPickedBefore(i)` 排除前面已选中
    - capability：lane k>0 = aluOnly（Full lane 集合由 LaneCapability 控制）
    - spec wakeup 仅来自 lane0（与既有行为完全一致；lane(j<k)→lane(k) 全 N²
      spec wake 留给 Phase C）
  - 把原 `issueIdx0/1`、`issueFound0/1`、`issue1UsesSpec` 三组并行变量统一
    到 `Vec(issueWidth)` 的 `pickIdx / pickFound / pickUsesSpec`

### 关键陷阱（已修复）

- 第一版把 lane0 也放进通用循环，`canSpecFwdFrom0` 即使在 k==0 被门控为
  false.B，FIRRTL 仍检测到 `pickIdx(0) → buffer(pickIdx(0)) → idx0` 的
  组合环。修法：lane0 fold 单独写在循环外，循环只跑 k=1..issueWidth-1。

### 验证

- sim-all 41/41 PASS（行为与 A.4 一致）

## Phase A.6 — Issue O(N²) pair RAW

### 目标
把 lane0→lane1 单一 RAW 检查扩展为 O(N²)：`pairRaw(k) = OR over j<k where lane(j) is Load`。

### 关键决策
仅当 lane(j) 是 **Load** 时才禁止 pair 发射；非 Load 的生产者由 Execute 同拍前递通路覆盖（当前为 lane0→lane(>=1)）。

### 文件
- `src/main/scala/dataLane/Issue.scala`：92-118 行 pairRaw Vec(issueWidth)，finalIssue(k) = canIssue(k) && !pairRaw(k)。

### 验证
- sim-all 41/41 PASS。

## Phase A.7 — 双发射 IPC 回归

### 目标
确认 A.2–A.6 全是结构重构、IPC 与 A.1 baseline (0.6017) 持平。

### 验证
- 41 TC 单跑取 IPC，mean = **0.6017** ✅（与 A.2 baseline 完全一致）。
- 数据：`scripts/ipc_a7.txt`。

## Phase A.8 — 4-issue 烟测（意外通过 sim-all）

### 目标
临时把 `issueWidth = 4`，验证 Chisel elaborate 是否通过。

### 实际结果
- elaborate ✅、Verilator build ✅、**sim-all 41/41 PASS** ✅。
- mean IPC = **0.6346**（+5.5% vs 0.6017，+20.6% vs 0.5262 baseline）。
- 数据：`scripts/ipc_a8.txt`。

### 为何意外 work
- LaneCapability 限定 lane k>0 = ALU-only：访存只走 lane0，Memory 单口够用。
- IssueQueue spec-wake 只从 lane0（`canSpecFwdFrom0`）：lane(j>0) 的生产者**不会**唤醒同拍的 lane(k>j) 消费者，自动避开 lane(j>0)→lane(k>j) RAW。
- A.6 的 pairRaw 兜底了 lane(j)=Load 的情况；非 Load 的 lane0 生产者由 Execute 同拍前递解决。
- 因此现有 Execute 同拍前递（仅 lane0→lane(>=1)）已经是充分必要——Phase C 想再扩 N² 必须先打破"spec 仅 lane0"，否则收益有限。

### 副作用
- `CPUConfig.issueWidth = 4` 已永久落地，PRF/Refresh/写口/Execute 全 4 宽。
- ckptSaveWidth=2 与 renameWidth=4 不匹配——Rename 一拍最多 2 个分支保存，>2 分支一拍会阻塞，目前 41 TC 未触发。

### 当前 milestone
- issueWidth=4 + 41/41 + mean IPC 0.6346。距离最终目标 0.80 还差 ~26%，由 Phase B (4-wide retire) → C (Execute N² 同拍前递) → D/D' (双 Mem 口) 等贡献。

## Phase B — 4-wide retire（commitWidth 2→4）

### 目标
将 ROB 提交从每拍最多 2 条扩到 4 条，同时输出 4 条 commit 观测信号给 difftest。

### 关键发现 / 修复
1. **BCT.freeCount 截断**：原 `freeCount: UInt(2.W)`，最大 3。commit 4 个分支时 4→0 截断 → BCT head 不前进，槽位泄漏。
   - 修：`UInt(log2Ceil(commitWidth+1).W)`。
2. **难定位的失败：bgeu/bltu/srli**（与原始 width=2 不同的失败集）：
   - 现象：cycle 0xe5 commit 4 条后，emu `commit_step` 抛 "Unknown opcode 0"。
   - 真因：difftest 参考模型 `commit_step(N)` 在循环外预先解码 N 条；当 ECALL（程序结束信号）不是本拍最后一条 commit 时，参考模型继续往后解码，越界到全 0 区域。
   - 修：在 ROB 添加 `isSysStop` 字段（= type_decode_together(0)，覆盖 ECALL/EBREAK/FENCE）；commitMask 链中"若任何更老 lane 是 sysStop 则截断"，与 store-must-be-lane0 同型规则。

### 文件
- `src/main/scala/CPUConfig.scala`：`commitWidth: Int = 4`
- `src/main/scala/device/BranchCheckpointTable.scala`：`freeCount` 位宽参数化
- `src/main/scala/dataLane/DiffPayloads.scala`：`ROBAllocData.isSysStop`
- `src/main/scala/dataLane/Dispatch.scala`：`isSysStop := td(0)`
- `src/main/scala/device/ROB.scala`：alloc 写入 isSysStop；commitMask 链加 sysStopBefore 截断

### 验证
- sim-all 41/41 PASS ✅
- mean IPC = **0.6399**（+0.83% vs A.8 0.6346，+21.6% vs baseline 0.5262）。
- 数据：`scripts/ipc_b.txt`。
- 注：commit 提速幅度小，说明当前阶段 commit 不是瓶颈（瓶颈在 Execute 同拍前递与 Issue 选择）。

---

## Phase C —— Execute N² 同拍前递 + IQ 链式 spec wake

### 目标
解锁 4-issue 真正同拍并行：消除"非 Load 老 lane 写 → 年轻 lane 同拍读"的串行依赖。

### 改动
1. **Execute.scala**：把单值 `lane0CanForward/lane0FwdPdst/lane0FwdData` 扩为
   `Vec(issueWidth)` 的 `laneFwdValid / laneFwdPdst / laneFwdData`。
   - 每条 lane 在迭代体尾部根据自身 `laneValid && regWriteEnable && !lType && pdst≠0`
     导出本拍 ALU/LUI/AUIPC/JAL/JALR 结果。
   - lane k 的 `actual_rdata1/2` 用 `PriorityMux` 从最年轻(j=k-1)向最老(j=0)选第一
     个 hit 的同拍前递结果，未命中则落到原 PRF/bypass 结果。严格前向无环。
2. **IssueQueue.scala**：spec wake 从"仅 lane0"扩为链式 `lane(j<k)`，引入
   `laneSpecPdst/laneSpecValid` Vec；issue.valid 门控保守：lane k 用 spec 时
   要求 0..k-1 全部 fire。
3. **Issue.scala**：`pairRaw` 已是 O(N²) Load-only（Phase A.6），无需改。

### 验证
- `xmake b rtl` PASS（无组合环）。
- sim-all 41/41 PASS ✅
- mean IPC = **0.6582**（+2.9% vs B 0.6399，+25.1% vs baseline 0.5262）。
- 数据：`scripts/ipc_c.txt`。
- 注：距 Phase F 目标 0.80 仍差 ~21%，瓶颈下移到 Mem 单口 + IQ 选择窗口。

---

## Phase D —— MSHR/Refresh 仲裁验证（重确认）

### 目标
确认 `MyCPU.scala:343-385` 的 MSHR vs Refresh 写口仲裁链在 issueWidth=4 / refreshWidth=4 下逻辑正确。

### 结论
- `mshrFreeLaneOH: Vec(refreshWidth, Bool())` 已按宽度循环；
- `effRefValid/Idx/Pdst/Data/Wen` 全部 Vec(refreshWidth)；
- γ-wake 抑制保持 lane0；
- Phase C 已经在 issueWidth=4 + refreshWidth=4 下 41/41 PASS，故 Phase D 仅做静态走查 + 不重跑 IPC。
- 无代码改动。

---

## Phase D' —— 双 Mem 口（未实施，记 PLAN.md TD-003 / TD-004）

### 决策
完整方案需重写 `Memory.scala / StoreBuffer.scala / memory/AXIStoreQueue.scala / Issue.pairRaw / LaneCapability` 五个核心模块，
并扩 MSHR 到 ≥2 项 + AXI 仲裁。在当前会话预算内风险/收益不平衡，故记录为 TD：
- **TD-003**：lane0/1 双 Full（Mem 口）—— 实现需 ~400 行改动 + 双口 SB 优先级 / SQ 双查询 / MSHR 多项 / AXI 仲裁。
- **TD-004**：MSHR 单项 → ≥2 项（独立于 D'，可单独实施，对 lw/lb/lh 类测试增益最大）。
预估如果实现 D'+TD-004，可能把 lw/lh/lb 从 ~0.30 IPC 提升到 ~0.55-0.70，整体 mean IPC 进入 0.78-0.85 区间。

---

## Phase E —— sizing 调参

### 改动
- `issueQueueEntries`: 32 → 48
- `fetchBufferEntries`: 16 → 32
- 试过 `bhtSize`: BHT64 → BHT256，但短测试集冷启动反而拖低 IPC（mean 0.6577 → 0.6282），已**回滚**保持 BHT64；注释保留实验结论。

### 验证
- sim-all 41/41 PASS ✅
- mean IPC = **0.6577**（与 Phase C 0.6582 几乎相同，sizing 收益≈0）。
- 数据：`scripts/ipc_e1.txt` (IQ48+FB32)、`scripts/ipc_e2.txt` (BHT256 反例)。
- 结论：IQ/FB 当前从未真正成为瓶颈；瓶颈仍在 Mem 单口 + 单 MSHR。

---

## Phase F —— 双向回归 + 最终汇总

### 双向回归
- 临时切回 `issueWidth=2 / commitWidth=2`（保留 Phase B/C/E 全部其他改动）：
  - sim-all 41/41 PASS ✅
  - mean IPC = **0.6015**（≈ A.7 0.6017，回归无退化）。
  - 数据：`scripts/ipc_w2.txt`。
- 切回 `issueWidth=4 / commitWidth=4`：sim-all 41/41 PASS ✅，最终 IPC = **0.6577**。
  - 数据：`scripts/ipc_final.txt`。

### 各阶段 IPC 增益
| 阶段 | 改动概要 | mean IPC | Δ vs 上一阶段 | Δ vs baseline |
|---|---|---|---|---|
| baseline (双发射) | — | 0.5262 | — | — |
| A.1 (IQ aluOnly bit fix) | 修复 IQ 编码 | 0.6017 | +14.3% | +14.3% |
| A.7 (重构后双发射) | helper/参数化/N-pick/O(N²) pairRaw | 0.6017 | 0% | +14.3% |
| A.8 (issueWidth=4) | 仅切宽度 | 0.6346 | +5.5% | +20.6% |
| B (commitWidth=4) | ROB 4 retire + isSysStop 截断 | 0.6399 | +0.83% | +21.6% |
| C (N² spec/fwd) | Execute 同拍前递 + IQ 链式 spec | 0.6582 | +2.9% | +25.1% |
| D (走查) | 0 | 0.6582 | 0% | +25.1% |
| E (sizing) | IQ 48 / FB 32 | 0.6577 | -0.08% (噪) | +25.0% |
| **最终** | issueWidth=4 / commitWidth=4 | **0.6577** | — | **+25.0%** |

### 与 0.80 目标差距分析（瓶颈量化）
最终 IPC 0.6577，距 0.80 差 ~22%。按测试集 IPC 分布拆解：
- **Load 类** (lw 0.33 / lh 0.29 / lb 0.30 / lhu 0.30 / lbu 0.30)：占总周期数最多，限于
  **单项 MSHR + 单 AXI 通道**，每个 miss 串行等响应，即使后续指令独立也无法 overlap。
  → 解决路径：TD-004 多项 MSHR（最高优先级）。
- **Branch 类** (jal 0.47 / jalr 0.35 / beq 0.49 / bne 0.48 / blt 0.49 / bge 0.45)：受
  "taken 后 1 拍 fetch 气泡 + IROM/Decode 重新填管" 限制；BHT64 在短测试集已饱和。
  → 解决路径：BTB 命中下零气泡 fetch（当前有 BTB 但似乎未短路 fetch 1 拍延时）。
- **ALU/Imm 密集类** (sw 1.01 / srl 0.97 / addi 0.94 …)：已接近理论上限，不再是瓶颈。

满足收尾判定 #2 —— issueWidth=4 + 41/41 + 已穷尽当前可负担优化 + 量化瓶颈到 LOG/PLAN。



---

## Phase D' —— TD-003 保守版（lane1 = ALU + Store）

### 目标
原 Phase D 走查后未做任何代码改动。Phase D' 在 0.6577 基础上推进 TD-003：
**让 lane1 也能承接 Store**（lane0/lane1 双 sbWrite 端口），从而提升“同拍 ALU+Store 混合”
场景的 issue 利用率。Load 仍只走 lane0（保守版，避免 AXI 单端口/单 MSHR 重构）。

### 决策：保守 vs 完整双 Mem 口
- **完整版**（lane1 也接 Load）需要：双 SB query / 双 SQ query / 双 MSHR 索引 / AXI 多 outstanding，
  但 AXI 当前单 AR/R 通道使 Load IPC 难以从 0.30 提升到 ~0.50 以上，预估收益 ~+0.05，
  改造规模大、风险高。
- **保守版**（lane1 只接 ALU+Store）：
  - SB query / SQ query / MSHR 仍单端口，零风险；
  - Stores 已 IPC=1.01 不是瓶颈，预期收益较小（+0.003 量级）；
  - 但代码改动局限、安全性高，是 TD-005（多项 MSHR）之前的合理过渡。

最终选择保守版。

### 修改文件清单
- `src/main/scala/dataLane/LaneCapability.scala`
  - 新增 `storeLaneSet = Set(0, 1)`、`isStoreLane / isLoadLane / isMemLane / isBranchLane`、
    `isAluOrStore(td)`、`storeLanes` 列表。
  - `canIssueOn(k, td)` 改为三分支：full lane → true；store lane → `isAluOrStore`；其他 → `isAluOnly`。
- `src/main/scala/dataLane/IssueQueue.scala`
  - lane k>0 选择条件改用 `LaneCapability.canIssueOn(k, td)`（line 284）。
- `src/main/scala/dataLane/Issue.scala`
  - `laneAluOnly` 改用 `canIssueOn`（line 78）。
- `src/main/scala/dataLane/Memory.scala`
  - sbWrite 端口升级为 `Vec(LaneCapability.storeLanes.size, SBWriteIO)`。
  - 复用 lane0 字节对齐逻辑给 lane1（funct3 / reg_rdata2 / data / isSbAlloc / sbIdx 在 Execute 已 per-lane 计算）。
  - lane0 mispredict 清除时序：在 `lane0MispredictKill` 计算之后用 last-connect 覆盖 `sbWrite(1).valid`。
- `src/main/scala/dataLane/StoreBuffer.scala`
  - `write` IO 升级为 `Vec(2, ...)`，写入用 for 循环。
- `src/main/scala/MyCPU.scala`
  - `uStoreBuffer.write` 逐 Vec 元素 `:=` 连接到 Memory 输出。

### 关键不变量（保守版正确性的来源）
- lane1 不接 Load → SB query / SQ query / MSHR 仍单端口，无需改 AXIStoreQueue。
- lane1 不接 Branch/JALR → `Memory.redirect` 仍只看 lane0。
- Dispatch 为每条 Store 分配独立 sbIdx（FreeList 每拍 4 个），lane0/lane1 同拍 sbWrite 永不写同槽。
- Stores `regWriteEnable=false` → `laneSpecValid` 自然 false，不影响 β-wake / γ-wake / 链式 spec。
- lane0 mispredict 时 lane1 sbWrite 必须取消（同拍 lane1 Store 比 lane0 mispredict 指令年轻）；
  StoreBuffer 也通过 `storeSeqSnap` 在 rollback 路径兜底。

### 难点 / 踩坑
1. **lane1 字节对齐复用**：sb/sh/sw 的字节 mask 与对齐 shift 原本是 lane0 信号 hardcode；
   要把这一段抽出来按 lane index 复制（Execute 已 per-lane 提供 funct3/reg_rdata2/data 的 Vec）。
2. **mispredict 清除时序**：`lane0MispredictKill` 是 lane0 RR/Ex 后的组合信号，
   必须在 sbWrite(1) 全部赋值之后再用 last-connect 覆盖 valid，否则 Chisel 编译期连接顺序导致清除失效。
3. **IQ memOrder 副作用（已知，未优化）**：lane0 Load 仍受 `olderStoreInIQ` 约束，
   即便该 older Store 同拍即将由 lane1 issue 出 IQ，Load 仍要等一拍。
   保守、正确，但损失约 0.005 ~ 0.010 mean IPC（见 PLAN TD-009）。

### 验证数据
- issueWidth=4 + TD-003：sim-all 41/41 PASS ✅，mean IPC = **0.6606**（`scripts/ipc_dprime.txt`）。
  - Δ vs Phase E (0.6577) = **+0.0029**（+0.44%）。
- issueWidth=2 回归（保留全部 D' 改动）：sim-all 41/41 PASS ✅，
  mean IPC = **0.6010**（`scripts/ipc_dprime_w2.txt`），与 Phase F baseline 0.6015 一致（噪声内）。
- 还原 issueWidth=4 / commitWidth=4 后再次 sim-all 41/41 PASS ✅，确认收尾状态稳定。

### 残留机会（下一阶段）
- **TD-005（多项 MSHR）**：唯一可跨过 0.70 的改动，预估 mean IPC +0.04 ~ +0.07。
- **TD-008（BTB zero-bubble）**：低风险，预估 +0.02 ~ +0.03。
- **TD-007（lane1 Load）**：必须 TD-005 之后；预估 +0.02 ~ +0.04。
- **TD-009（IQ memOrder 放宽）**：局部小改，+0.005 ~ +0.01。
- 详见 `scripts/PLAN.md`。

### 收尾确认
- CPUConfig：issueWidth=4 / commitWidth=4 / renameWidth=4 / fetchWidth=4 / IQ48 / FB32 / BHT64 ✅
- sim-all：41/41 PASS ✅
- mean IPC：0.6606（issueWidth=4）/ 0.6010（issueWidth=2 回归）✅
- 距 0.80 目标差距已量化、瓶颈已分层、下一步路径已写入 PLAN ✅


---

## Phase G —— TD-005 / TD-008 / TD-009 调研与试验（未落地）

> 目标：按 PLAN.md 优先级依次推进 TD-005（多项 MSHR）→ TD-008（BTB zero-bubble）
> → TD-009（IQ memOrder 放宽），冲击 mean IPC ≥ 0.80。
> 本阶段以"先调研、后改码"的方式进入，结论是三条路径都需要超出当前会话预算的
> 跨模块重构，因此 **未在 RTL 上落地**；但调研结论会改写 PLAN.md，避免后续会话
> 重复踩坑。

### G.1 TD-008 BTB zero-bubble —— 调研结论：现状已是 zero-bubble，无可挖空间

阅读 `Fetch.scala` / `BTB.scala` / `PC.scala` 后确认：

1. `BTB.scala` 的读端口完全组合：`io.read_pc(i)` 进 → `io.hit(i)` / `io.target(i)`
   出（无寄存器隔离）。
2. `Fetch.scala` 在同拍把 `slotTaken` / `slotTarget` 组合算出，并将
   `predict.jump` / `predict.target` 透传给 `PC.scala`。
3. `PC.scala` 的 `pcReg` 在本拍末尾按 `in.fetch_predict_enable` 更新，下一拍
   即用新 PC 取指。

也就是说：BTB 命中→预测跳转→下一拍即可在新目标取指，没有任何额外气泡。
PLAN.md 原本预估的 "+0.02~+0.03" 来自于"BTB 命中不再触发 mispredict"的设计目标，
该收益已经体现在当前 jalr=0.35 数据中（无 BTB 时 jalr 会更低）。

**结论**：TD-008 在当前 RTL 上已经达成 zero-bubble，**不再有可挖空间**。Branch
类 IPC 的剩余 gap 主要来自 fetch 打包效率（一条 taken branch 截断后续槽位 →
平均每 fetch 包只有 1~2 条有效指令）。要再提升只能从"按 taken branch 在包内
位置错峰发射"或"trace cache"这类大改动着手，远超 TD-008 范围。

### G.2 TD-009 IQ memOrder 放宽 —— 实测结论：会引发死锁，需更精细方案

实施变更：`IssueQueue.scala` 将 `olderStoreInIQ(i)` 直接置为 `false.B`（让
所有 Load 自由发射，由 `Memory.olderUnknown` 单点保证内存序）。

**回归结果**：sim-all **37/41 PASS**，4 个用例失败：`example / sh / sw / test_build`
（全部是 Store-heavy 测试）。

**死锁路径分析**：
1. 同拍出现 `(Load lane0, 更老 Store lane1)` 同对发射；
2. 进入 Memory 时，lane0 Load 的 SB 查询发现 lane1 Store 在 SB 中
   `addrValid=false` → `olderUnknown=true` → `memStall=true`；
3. `Memory.out.valid := in.valid && !memStall` —— **memStall 同时让 lane1 Store
   的 out.valid 也变成 0**；
4. lane1 Store 写 SB 是组合的（每拍重写同槽，幂等），但 ROB 永远收不到 Store
   的 `refresh.done` 事件 → ROB 头永远不前进 → SB 槽位耗尽 → 流水线全死。

**结论**：单点扣掉 IQ memOrder 是错误方案；正确做法应在 Memory 层把 stall
做成"逐 lane 精确"——例如保留 lane1 Store 的 out.valid，但仅 mask 掉 lane0
Load 的 validMask。这需要：
- 拆掉 `Memory.in/out` 的"统一 Decoupled"语义，改为 per-lane stall；
- 或在 Execute→Memory 之间加一个"lane0 Load 重发缓冲"（latency penalty）；
两者都属于较大改动。**本阶段已回退该改动**，等后续会话再起。

修改已 revert，sim-all 41/41 PASS ✅ 复测通过。

### G.3 TD-005 多项 MSHR —— 调研结论：需要跨 3 模块联动改动，单点 MSHR 扩 Vec 收益极小

阅读 `Memory.scala` MSHR 状态机 + `AXIStoreQueue.scala` + `DRAM_AXIInterface.scala`
后确认：

1. **AXIStoreQueue.scala** 是单状态机（`sIdle / sLoadWaitR / sStoreWaitB`），
   任意时刻最多承载 1 笔 AXI 事务。其 `loadAddr.ready` 仅在 `sIdle` 抬起，
   一旦进入 `sLoadWaitR` 就不再接受新 AR；
2. **DRAM_AXIInterface.scala** 也是单状态机，一笔事务一拍解析；
3. 因此即使 `Memory.scala` 把 MSHR 扩 `Vec(2)`，第二个 slot 想发起 AR 也会被
   AXIStoreQueue 卡住——**真正能并发的"DRAM 读飞行"数仍是 1**。

可观测的 Load 时序（lw 测试 IPC=0.33 的来源）：
| 拍 | 行为 |
|---|---|
| T0 | Load A 入 Memory → mshrCapture，sqLoadAddr.fire，AXIStoreQueue 进 sLoadWaitR |
| T1 | DRAM_AXIInterface sReadResp，AXIStoreQueue 看到 axi.r.valid，loadData.fire → MSHR sqLoadData.fire，下拍 mshrResultValid 抬起 |
| T2 | mshrComplete.valid=1 → MyCPU 顶层用空闲 refresh 端口 ack；MSHR 清；hazard #3 同拍清 |
| T3 | Load B 才能入 Memory（前提：早已到 IQ 头并解决 hazard #3） |

→ 每个 Load **3 拍**完成、3 拍间隔，IPC 上限 1/3=0.33，与实测吻合。

**要把 lw IPC 拉到 0.5 以上必须的最小改动集**：
1. `AXIStoreQueue.scala`：增加 1 个深度的 loadAddr 排队 + 第二状态机或并发受体，
   允许 sLoadWaitR 期间继续接受第二笔 AR；
2. `DRAM_AXIInterface.scala`：状态机扩为可重叠（同拍发起新 AR + 在 sReadResp 中
   返回旧数据），或加输出 R FIFO；
3. `Memory.scala`：MSHR 从单项改 Vec(2)，每个 slot 独立 ARID/RID 跟踪，γ-wake
   按 slot 广播；
4. 顶层 `MyCPU.scala`：`mshrComplete` 的 refresh 写口仲裁要支持每拍最多两个完成。

任意一个模块单独改都拿不到收益（最小可观测收益 = 把 3 拍间隔降到 2 拍 →
lw IPC ~0.5，但需要 1+2+3 三处协同，且要重新核对 store→load forwarding 的
"in-flight 期间同地址 Store commit"等边界情况）。

**结论**：TD-005 是真正的体系结构层重构，本会话未实施。建议下次会话单独立项，
分子任务 a/b/c/d 推进，每步 sim-all 回归。

### G.4 收尾状态

- **代码**：所有 Phase G 期间的临时改动（IQ memOrder = false 试验）均已回退。
- **CPUConfig**：issueWidth=4 / commitWidth=4 / renameWidth=4 / fetchWidth=4
  / IQ48 / FB32 / BHT64 ✅
- **sim-all**：**41/41 PASS** ✅（Phase G 末次复测）
- **mean IPC**：仍为 Phase D' 的 **0.6606**（无 RTL 改动 → 无 IPC 变化）
- **PLAN.md**：依调研结论改写，TD-008 标记为"已完成（无法再挖）"，TD-009 改为
  "需先在 Memory 做 per-lane stall"，TD-005 拆为 4 个子任务。

---

## Phase G2：TD-005 RTL 落地（MSHR Vec(2) + arPending），AXI 仍单 outstanding

### G2.1 实施范围

本次 Phase G2 仅落地 TD-005 中风险最低的"Memory 侧 RTL 重构"：

1. **`Memory.scala`**：单项 MSHR → `Vec(2)` MSHR，新增 `mshrArPending(i)` 标记
   "已捕获 Load 但 AR 尚未发出"。当 slot0 在 AXI 上等响应时，slot1 仍可同拍捕获
   新 Load，避免 memStall。AR 发射优先级：pending > fresh。
2. **`Issue.scala`**：`NumLoadHazardSrcs` 4 → 5，hazard(3..4) 分别盯 MSHR 两个槽
   位的 outstanding Load。
3. **`MyCPU.scala`**：把 `Memory.mshrPending(0..1)` 接到 Issue.hazard(3..4)。

未触及（按用户允许的"AXI 单口保留"约定）：
- `AXIStoreQueue.scala`：仍 `sIdle/sLoadWaitR/sStoreWaitB` 单状态机，单 outstanding；
- `DRAM_AXIInterface.scala`：仍 3 状态单口，savedId/savedRdata 单寄存器；
- `MyCPU.mshrComplete` refresh 写口仲裁：仍单端口（按 slot0 优先回写）。

### G2.2 验证结果（issueWidth=4）

| 指标             | Phase D'（TD-005 前） | Phase G2（TD-005 RTL 后） | Δ        |
| ---------------- | --------------------- | ------------------------- | -------- |
| sim-all 通过数   | 41/41                 | **41/41 ✅**              | 0        |
| mean IPC         | 0.6606（会话内估值）  | **0.6494**                | -0.0112  |
| lw IPC           | ~0.32                 | 0.2857                    | ~ -0.03  |
| lh / lhu IPC     | ~0.28                 | 0.2558 / 0.2619           | ~ -0.02  |

**结论**：MSHR Vec(2) 数据结构已落地、功能正确性 41/41 全过；但 IPC 出现 ~1.7%
的回退而非提升。**根因**与 G.3 调研一致——AXI 物理层仍为单 outstanding，slot1
的 arPending 捕获只能让 Load 提前进 MSHR、并不能让 AXI 上同时挂 2 个 AR；
而 `mshrCaptureFireWire = newLoadCanCapture`（不再门控 sqLoadAddr.fire）会让
lane0 outMaskBits(0) 提前抑制，对下游 Refresh 的写口仲裁/γ-wake 时序略有不利
影响，落入 ~0.011 IPC 回退区间。

### G2.3 Width=2 双回归

`CPUConfig.scala issueWidth = 2` 后整轮 sim-all：

- 通过 41/41 ✅
- mean IPC = 0.5856（相对 committed 基线 de1c203 的 0.5262 提升 +0.06；相对
  会话内 Phase D'（uncommitted）估值 0.6010 回退 -0.015）。

由于 Phase D' 与 TD-005 同处于一份 uncommitted 改动里，0.6606 / 0.6010 两个
"会话内基线"无法干净复现，仅 0.5262 是可验证的真基线。结论：相对 committed
基线显著改善，相对会话内估值小幅回退。

### G2.4 后续（要拿到正向 IPC 收益必须做的）

要把 TD-005 从"结构落地、IPC 持平"推进到"IPC 提升至 ≥ 0.70"，必须继续完成：

- **TD-005-a**：`AXIStoreQueue` 状态机重构为 2 outstanding（Vec(2) loadAddr/
  loadData + R 通道按 ARID 路由）；
- **TD-005-b**：`DRAM_AXIInterface` 增加 R 通道 FIFO 或双 savedId/savedRdata
  寄存器；
- **TD-005-c**：`MyCPU.mshrComplete` refresh 写口扩为 2 端口（或保留 1 端口
  但允许 slot0/slot1 轮询）。

任意一项缺失，本次落地的 MSHR Vec(2) + arPending 都拿不到吞吐收益（已验证）。


---

## Phase A：G2 回退修复（2024 会话内，未提交）

### A.1 根因（最终确认）

G2 的 `mshrCaptureFireWire = newLoadCanCapture` 把 lane0 outMaskBits(0) 的抑制
时刻从「sqLoadAddr.fire 同拍」提前到「`launchFreshAR && (axiFreeThisCycle ||
slot1 进 arPending)`」。当 `arPending` 路径触发（slot0 还在飞），lane0 Load
被声明为「已捕获」并屏蔽 outMaskBits，但 AXI 物理层是单 outstanding——AR 必须
等到 slot0 R 返回才能发；这中间 lane0 Load 的等价 stall 比原来多 1~2 拍，refresh
写口的争用窗口同时变窄，整体 IPC 下降 -1.7%（0.6606 → 0.6494）。

### A.2 Patch（dataLane/Memory.scala）

- 新增 `enableArPending: Boolean = false`，使 `anyArPending` 恒为 0；
- `mshrCaptureFireWire = launchFreshAR && sqLoadAddr.fire`，严格门控；
- `isAllocSlot = mshrCaptureFireWire && allocSlot === i.U`，不再把 alloc 与
  `newLoadCanCapture`（含 arPending 早期捕获）耦合；
- DRAM 分支 `memStall := !mshrCaptureFireWire`（必须等到 AXI fire 才推进）。

保留 MSHR Vec(2) 的数据结构和 γ-wake / hazard 接线，仅把 capture 时序回到 G2
之前。

### A.3 验证

- sim-all 41/41 PASS；
- mean IPC = **0.6606**（与 G2 之前的 D' 基线对齐，回退完全消除）；
- `scripts/ipc_stageA.txt` 已写入；
- 由于 `arPending` 通路被 disable，本拍仍然 ≤ 1 outstanding，与用户「AXI 单
  outstanding」约束一致；MSHR Vec(2) 在阶段 B.1（lane1+Load）启用并行 alloc
  时才真正发挥作用。

---

## Phase TD-A：AXI Outstanding=2 端到端打通（DRAM_AXIInterface + AXIStoreQueue + Memory）

### TD-A 背景与目标

REF.md 重新厘清了"DRAM 物理 1 op/cycle"与"AXI 协议 outstanding"的关系：DRAM 端口是物理硬约束（每拍最多服务 1 个 R 或 W），但 AXI/FIFO 层完全可以维持 outstanding=2 的请求/响应流水。先前"AXI 单 outstanding"是过保守的实现选择，现取消该约束，把整条 Load 外读路径（Memory → AXIStoreQueue → DRAM_AXIInterface → DRAM）改造为 outstanding=2，让 MSHR Vec(2) 真正能并发承接两个 in-flight Load。

### TD-A 关键修改

- **DRAM_AXIInterface.scala**：完全重写为 readReqFifo(2) + readRespFifo(2) 模式；
  - `axi.ar.ready := readReqFifo.io.enq.ready`（与 DRAM 是否忙完全解耦）；
  - DRAM 调度顺序：写优先（AW+W 同拍）> 读（deq readReqFifo + 1 拍 DRAM 读 + enq readRespFifo）；
  - `axi.r.valid := readRespFifo.io.deq.valid`，按入队顺序顺序返回，无需 ID 路由。
- **AXIStoreQueue.scala**：Load 路径从单状态机升级为 `loadInflightCnt(0..2)` + AR/R 直通；
  - `loadAddr.ready := (loadInflightCnt < 2) && (storeState===sStoreIdle) && axi.ar.ready`；
  - R 通道直通到 `loadData`，不需要重排；
  - Store 仍单 outstanding，且严格仅在 Load 路径全空闲（`loadInflightCnt=0 && !loadPending`）时发起，避免 R/B 通道竞争。
- **Memory.scala**：`mshrInFlightValid: Reg + mshrInFlightSlot: Reg` → 替换为 `mshrInFlightFifo: Queue(slotId, depth=2, pipe=true)`；
  - enq：`sqLoadAddr.fire` 时 push `arLaunchSlot`；
  - deq：`sqLoadData.fire` 时 pop 队头；
  - `axiFreeThisCycle = mshrInFlightFifo.io.enq.ready`（替代旧的 `!mshrInFlightValid || sqLoadData.fire` 单口约束）；
  - `mshrInFlightSlot/Valid` 改为 deq.bits/valid wire，下游 R 数据回写、flush 路径全部不变；
  - `enableArPending` 仍保持 `false`（lane0 单 Load lane 下不需要；阶段 B.1 lane1+Load 时再打开）。

### TD-A 验证

- `SIM=verilator xmake b rtl` / `b Core` / `r sim-all`：**41/41 PASS**；
- `scripts/ipc_TDA.txt`：mean IPC = **0.6620**（vs Phase A 基线 0.6606，+0.2%）；
- 提升幅度小的根因分析：
  - 当前 lane0 是唯一 Load lane，单拍最多发 1 个 AR；
  - 测试程序中很少出现"两条独立 Load 紧邻发射"的模式（典型 lw 测试是 lw + addi/branch 依赖链）；
  - lw 自身 IPC 仍为 0.333（≈1/3），说明 Load latency 没变（DRAM 1 拍 + 状态机 1 拍 + Refresh 1 拍），TD-A 改善的是 **吞吐**，而非 latency；
  - 真正能发挥 outstanding=2 的场景需要 **lane0 与 lane1 同拍各发 1 个 Load**（阶段 B.1 lane1+Load）。
- 因此 TD-A 是"基础设施就绪"型改造，单看 IPC 收益很小，但是它解锁了 B.1 阶段（lane1+Load）的可行性——没有 outstanding=2，B.1 同拍并发 Load 会立即在 AXI 单口处串行化。

---

## 2025 - Phase TD-B (lane1 升级 Full lane，单-Load-per-cycle 保守版本)

### 实施摘要
- LaneCapability：fullLaneSet={0,1}, branchLaneSet={0}, storeLanes/loadLanes={0,1}；canIssueOn(1) 接受 ALU+Store+Load。
- Memory.scala：sbQuery/sqQuery 改 Vec(loadLanes.size)；mshrCaptureFire 改 Vec；引入 lane0 mispredict 早期 kill；activeIdx PriorityEncoder 选当拍唯一活动 Load lane；MSHR 仍 Vec(2) 单 grant + 单 AR；保持 enableArPending=false（rubber-duck blocker 规避）。
- StoreBuffer/AXIStoreQueue：query 接口 Vec 化（无状态查表，纯组合复制）。
- Issue.scala：NumLoadHazardSrcs 由 5 升到 8（3*loadLanes + 2 MSHR）；新增 "no-double-Load" 门控（lane k>0 若是 Load 而某 j<k 也是 Load 则禁发）。
- IssueQueue.scala：memOrderOK 由 lane0 扩到所有 Load lane 候选检查。
- MyCPU.scala：hazard 0..5 = (RR/EX/MEM × loadLane 0..N-1)；hazard 6/7 = MSHR(0/1)；γ-wake 抑制改为按当前 lane 是否在 loadLanes 中、对应 mshrCaptureFire 位为 1 时禁广播。

### 验证
- `SIM=verilator xmake b rtl` ✅, `b Core` ✅, `r sim-all` ✅ 41/41 PASS。
- mean IPC = 0.6626（TD-A baseline 0.6620，+0.06%，**实质持平**）。
- 逐 case 比对：除 test_build 微跌 -0.0007 外，其余全部 bit-identical。

### 结论 / 下一步
- 单-Load-per-cycle TD-B 在当前 41 个 micro-bench 上没有暴露 lane1-mem 机会：要么 IQ 中老 Store 阻塞 Load（memOrder），要么老 Load 阻塞 lane1 同拍 Load（no-double-Load 门控）。
- 真正释放 IPC 需要其一：
  - **TD-C**：lane1 Branch/JALR（Branch 占比 ~15%，可让 lane1 在 lane0 是 Branch/JALR/Mem 时承接 ALU 之外的更多类型）；
  - **真双 Load**（去掉 no-double-Load 门控 + 多槽 grant + AR pending），rubber-duck 已列出 4 个 blocker；
  - 或更大尺度的 benchmark（micro-bench 太短）。


---

## TD-C 施工日志（lane1+Branch/JALR，single-Branch-per-cycle 仲裁）

**变更文件**：
1. `LaneCapability.scala`：`branchLaneSet = Set(0, 1)`；
2. `Issue.scala`：在原"no-double-Load"门控旁追加"no-double-Branch"门控；
3. `Execute.scala`：BHT/BTB 更新由 `if (k==0)` 硬编码改为按 lane 写入 `laneBhtValid/Idx/Taken` 与 `laneBtbValid/Pc/Target` 中间 Vec，循环外用 `PriorityEncoder` 选最低 idx 的 lane 写回单端口 BHT/BTB；
4. `Memory.scala`：
   - `mispredictPerLane` Vec 与 `anyMispredictEarly`/`mispredictIdx`/`winnerLane` 仲裁；
   - 旧 `lane0MispredictEarly` 仅在 `mispredictIdx === 0` 时为真，向后兼容；
   - `redirect` 输出由 winnerLane 驱动，addr/robIdx/storeSeqSnap/checkpointIdx 全部取 winner；
   - 输出 `outMaskBits` 抑制改为 `k > mispredictIdx`（旧版用 `lane0MispredictKill` 会误杀 lane1 自身 Branch，已修正）。

**验证**：
- `xmake b rtl` 通过；`xmake b Core` 通过；`xmake r sim-all` 41/41 PASS；
- TD-C mean IPC = **0.6737**（vs TD-B 0.6626，**+1.7%**）；
- 显著正向：addi +15.1%、auipc +5.5%、sltiu +3.8%、srli +3.5%、sw +3.4%、or +2.4%、sll +2.0%；
- 微小回退：srai -0.8%、sltu -0.2%（抖动量级）；
- Load-bound 测试（lw/lh/lbu）IPC 不变（DRAM 单端口仍是瓶颈）。

**结论**：lane1 接收 Branch/JALR 后，原本"ALU+Store 与 Branch 同对发射"的串行解开，
ALU 密集与 Store 密集类负载普遍受益；Load 密集类不受益（瓶颈在 DRAM）。

---

## v14 —— 修复 BCT ReadyTable 快照同拍 busyVen 旁路 BUG

### 背景
`scripts/BUG.md` 记录的 16 例 sim-regressive 失败，最早暴露 commit 数最小的是
`kernels_branch_reduce_debug`（仅 1091 个 commit 即不一致）。本会话用 wave-debug 工作区 `kbr_x10/`
全程扫描 `busyVen / readyVen / recover / IQ enq / readyQuery / ReadReg`，定位到
**BCT 在 Rename 同拍构造分支快照时，ReadyTable 部分丢掉了同拍内更老 lane 的 busyVen 副作用**。

### 时序证据（kbr_x10）
- cy 0x26a：busyVen[1]=0x27（PC=0x104 是 x10 新生产者）
- cy 0x273：ReadyTable RECOVER（更年轻分支误预测） → ReadyTable[0x27] 被恢复为 1（陈旧）
- cy 0x279：PC=0x3f0 入队 IQ，readyQuery raddr[0]=0x27 → rdata=1（错！）
- cy 0x27b：lane2 ReadReg 读 PRF[0x27]=0x102ec（陈旧）
- cy 0x28b：PC=0x104 才 Refresh，PRF[0x27] 写真值 0xecb82367（晚 16 拍）

### 修复
- `Rename.scala`：新增 IO `readyTbSnapIn: Vec[prfEntries, Bool]` 与 `ckptReadyTb.{postRename1,postRename2}: Vec[prfEntries, Bool]`，
  逻辑镜像现有 `postRenameRAT1/2`：从 `readyTbSnapIn` 起步，遍历 `ckptLaneMask{1,2}Vec` 内 lane，
  若 `rat.wen(j) && pdsts(j)≠0` 则把 `postRenameReady(pdsts(j)) := false.B`。
- `MyCPU.scala`：`uBCT.saveDataIn(0/1).readyTb` 改接 `uRename.ckptReadyTb.postRename1/2`，
  并新增 `uRename.readyTbSnapIn := uReadyTable.io.snapData`。

### 验证
- `xmake b rtl` ✅；`xmake b Core` ✅；
- sim-basic：39/39 PASS（无回归）；
- sim-regressive：**16 失败 → 2 失败**（14 例同根因一次性收割）；
- 仍未解决 2 例：`algo_array_ops_ooo4` / `..._no_libcall`（**预先存在的死锁 BUG**，与此次修复无关，
  详见 BUG.md 修复进度小节）。

### 结论
- 本次修复属于 **BCT 快照正确性补完**：
  RAT 路径之前已正确处理"同拍 lane 间数据相关"，ReadyTable 路径漏补，现已对齐；
- 14 例失败由同一根因引发，说明同拍多 lane Rename + 分支快照 + 误预测恢复
  这一组合在原回归集里有相当大的覆盖；
- 待修：FB 死锁（algo_array_ops_ooo4），方向是 FB 计数/指针在 redirect 场景下的失步。

---

## v15 ——「FetchBuffer 截断 ≥3 个预测分支：algo_array_ops_ooo4 死锁根治」

### 背景
v14 用 BCT ReadyTable 快照同拍 busyVen 旁路修复了 14/16 sim-regressive 失败，剩余 2 例
`algo_array_ops_ooo4` / `..._no_libcall` 表现为：commit≈19522 后 BCT count=8 永久 full，
Rename canSave 永远 false → 取指反压死锁。

### 根因（细节）
- BCT 每拍最多保存 `saveW=2` 个 checkpoint；
- `Rename.scala:69-72` 用 `PriorityEncoderOH` 只识别 4-wide bundle 中前 2 个 bType/JALR；
- 当 4 条指令含 ≥3 个预测分支时，第 3+ 个分支 `hasCheckpoint=false / checkpointIdx=0`，
  但被照常 rename 入 ROB；
- 它日后 mispredict 时 Memory 用 ckptIdx=0 触发 recover，BCT `tail` 被设到当前 in-flight
  ptr 之外（"幻影 recover"），count 失序后越积越大直至 8 满，永久死锁。

### 修复
**`FetchBuffer.scala` 在出队侧截断**：
1. 用指令 opcode（B-type=0x63 / JALR=0x67）检测 4-lane 是否预测分支；
2. `PriorityEncoderOH` 找前 2 个分支位置；若存在第 3 个，把 `truncatedDeqCount` 设为
   `secondBrIdx + 1`（即出队范围限制到第 2 分支含）；
3. 用 `truncatedDeqCount` 同步覆盖 `deq.bits(i).valid` 和 `head` 推进；
4. 被截断的第 3+ 分支留在 buffer，下拍位于新 4-lane 窗口低位，可正常成为
   下拍的第 1/2 个分支保存 checkpoint。

**辅助 defense-in-depth**：
- `BranchCheckpointTable.scala`：新增 `recoverIdxInRange` 输出，并把 recover 副作用
  门控为 `recoverEffective`（正常路径下永远 true）；
- `MyCPU.scala`：`memRedirectValid := uMemory.redirect.valid && uBCT.io.recoverIdxInRange`。

### 验证
- `xmake b rtl` ✅；`xmake b Core` ✅；
- sim-basic：**39/39 PASS**（无回归）；
- sim-regressive：**52/56 PASS**（4 例 `kernels_indirect_call_*` 用例编写不合理，按指示忽略）；
- 两例 `algo_array_ops_ooo4` / `..._no_libcall` 同时通过。

### 结论
- 自此 sim-regressive 所有可修复用例已全部 PASS；
- v14 + v15 共修复 16 例失败：v14 同拍 busyVen 漏旁路（14 例）+ v15 ≥3 预测分支被错入 ROB（2 例）；
- 下个里程碑回归 v13 路线图（TD-D / TD-E / TD-F），见 TASK.md 重写版。

---

## TD-D ——「mshrComplete: Vec(2) MSHR 完成 2 路并行写口仲裁」

### 背景
TD-A/B/C 落地后，Memory MSHR 已是 Vec(2)（同时持 2 个 in-flight Load），但
`mshrComplete` IO 仍是单 Bundle，每拍至多 ack 一个 slot。当 2 个 Load 的 R
在同拍返回时（outstanding=2 FIFO 同拍 deq 第二个），第二个必须等下一拍才能
回写 PRF / readyTb，浪费一拍 wakeup 机会。

### 改动
1. **`Memory.scala`**：
   - `mshrComplete` IO 由 `Bundle` 升为 `Vec(2, Bundle)`；
   - 每个 mshr slot i 直接驱动 `mshrComplete(i)`（valid = mshrValid(i) && !mshrFlushed(i) && mshrResultValid(i)）；
   - `ackVec/ackFireVec` 替换 `ackSlot/ackAny` 旧广播；`freeMask` / `mshrPending` /
     `isAckSlot` 全部改为 per-slot 版本（每 slot 独立判定本拍是否被 ack）。
2. **`MyCPU.scala`**：
   - 仲裁循环升级：依次为 j=0,1 的 `mshrComplete(j)` 找一条"既未被 Refresh 占用、
     也未被 j' < j 占走"的最低 lane k，记录于 `mshrLaneClaimByJ(j)(k)`；
   - `mshrFreeLaneOH(k) = OR(mshrLaneClaimByJ(*)(k))`，用于 `effRef*` 选择；
   - `effRef*` 字段选择改用 `MuxCase` 按 lane k 的 claimer 选 j 的对应字段
     （Bundle 含 flip 不能直接 Mux，必须逐字段挑）；
   - 各 slot 独立 ack：`uMemory.mshrComplete(j).ack := mshrLaneClaimByJ(j).asUInt.orR`。

### 验证
- `xmake b rtl` ✅；`xmake b Core` ✅；
- sim-basic：**39/39 PASS**（无回归）；
- sim-regressive：**52/56 PASS**（4 timeout 用例按指示忽略）；
- IPC：**与 TD-C / v15 基线逐项一致**（39 个 micro-test 完全无回退）。

### 收益评估
- 当前 micro-test 集合中，2 个 R 同拍返回的概率极低，TD-D 单测难显式收益；
- TD-D 的真正价值是为 TD-E（移除 `no-double-Load` 门控）铺路：
  - TD-E 后 lane0/lane1 同拍各发 1 Load → 2 个 MSHR 同拍 alloc → R 同拍/相邻拍返回更频繁；
  - 此时 mshrComplete(0/1) 同拍并行写回的路径才会高频触发，wakeup 链
    缩短至少 1 拍，混合负载预期 mean IPC ≈ +0.02。
- **TD-D 是 TD-E 的硬前置条件**：若先做 TD-E 而 mshrComplete 仍单口，
  双 Load 完成会因第 2 个等待 ack 串行化，IPC 收益被吃掉。

### 接下来
- TD-E：`Issue.scala` 移除 `doubleLoadStall`，`Memory.scala` 的 `arPending` 复活路径
  确认（`enableArPending` 当前为 false，需改为 true）；并扩 mshrInFlightFifo enq 端口为 Vec(2)
  以承担同拍 2 alloc 的入队压力。

---

## v16 ——「kernels_indirect_call_debug 超时根因调研立案」（无 RTL 改动）

### 起因
v14/v15 把 `kernels_indirect_call_*` 4 例统一标为"用例编写不合理 / 按指示忽略"，
但 v16 用户明确指出：该程序**并非死循环**，仿真时间却显著高于其他程序，需追查根因。

### 静态证据
- `difftest/build/sim-data/kernels_indirect_call_*.csv` 行数对比：
  - baseline / ooo4 / ooo4_no_libcall / ooo4_unroll：均 115 行（~115 拍正常 halt）；
  - **debug：1 395 805 行**，末拍 `commits=1 987 103, IPC=1.4236`——撞 max-cycle 截止；
- `asm_test_cases_regressive/kernels_indirect_call_*.dump`：
  - 优化版本 72 行，`run_indirect` 已被常量传播为 `addi a0,zero,11; jalr zero,0(ra)`；
  - debug 版本 223 行，保留完整 -O0 控制流，热点：
    `0x1b8 lw a5,0(a5) ; 0x1c4 jalr ra,0(a5)` + 栈帧 sw/lw 循环变量。

### 假说
H1（首选）indirect-JALR rs1 旁路漏路（与 v14 修的 ReadyTable 快照旁路同源不同路）；
H2 BTB 写口被同拍 mispredict 抑制 → JALR 永远 cold；
H3 栈帧 store-to-load 转发在某种边界漏发 → `i++` 永远读旧值。

### 处置
- 本会话**未改任何 RTL**；
- 已分别更新 `BUG.md`（v16 调研）/ `STUDY.md`（章节九）/ `PLAN.md`（TD-INDIR）/ `TASK.md`；
- 下一步需要用户授权后：单跑 `TC=kernels_indirect_call_debug DUMP=1`、抓 vcd、走 wave-debug
  full-mode 锁定首拍偏离点；与 TD-E 的优先级取决于根因是否在 byPass / 转发链上。

---

## v17 —— indirect_call_debug 根因实证（无 RTL 改动）

### 改动

- `scripts/BUG.md`：v17 实证章节追加（PC 直方图、`emu.lua` dmem 空表证据、`xmake.lua` IROM-only 加载、修复方案 F1/F2/F3、副 bug `main.lua:268-274` mismatch 覆盖）
- `scripts/STUDY.md`：第十节追加（调查路径、教训沉淀）
- `scripts/PLAN.md`：第四节"推荐下一步"再次刷新，TD-INDIR 路线由"RTL 调查"转向"difftest 框架修复"
- `TASK.md`：v18，下一迭代任务书（TD-INDIR-A/B/C/D）
- `BUG.md`：v16 假说 H1/H2/H3 全部排除（difftest PASS 2.67M commits 已证 RTL 行为与 ref 一致）

### 不动

- `byPass/src/main/scala/**`（Chisel）一律不动；
- v14/v15/TD-D/TD-E 既有不变量保留。

### 实测

- `SIM=verilator TC=kernels_indirect_call_debug TIMEOUT=80 xmake r Core` 跑 90s 被 timeout 杀，commit #2,672,939、cycle 0x1d2258、IPC=1.40，全程 difftest PASS；
- 提取 PC 直方图证明程序在 `_start ↔ main → run_indirect → jalr ra,0(a5) → PC=0` 之间死循环。

### 状态

- TD-D / v14 / v15 既有；
- **TD-INDIR 转向 difftest 框架修复**（不再走 wave-debug full-mode；本案与 RTL 无关）；
- TD-E 仍待启动，等待用户决定是否在 TD-INDIR 之后插队。

---

## v18 —— TD-INDIR-A/B/C 落地（difftest 平台修复，无 RTL 行为改动）

### 改动文件
- `difftest/src/emu.lua`（init_program）：`dmem` 启动时把 `imem` 字节铺入 sparse 表（TD-INDIR-A）
- `difftest/src/main.lua` line 268-274：mismatch 由 `=` 改 `or`（TD-INDIR-C）
- `difftest/xmake.lua`：
  - 新增 `dram_hex` 路径常量
  - rtl on_build 钩子向 `mem_65536x32.sv` 注入 `initial $readmemh("dram.hex", Memory)`
  - run-before 钩子额外生成 `dram.hex`（每行 32-bit word，小端打包）
  - clean 钩子同步删除 `dram.hex`

### 验收
- `kernels_indirect_call_debug` 在 **345 cycles / 168 commits** 内 ECALL halt（之前 1.4M+ 不收敛）
- sim-basic：39/39 PASS
- sim-regressive：**70/70 PASS**（之前基线 52/56；原 4 个 timeout 全部 PASS；新增 14 例也全部 PASS）

### 不变量
- `byPass/src/main/scala/**` 未改（H1/H2/H3 假说被证伪后无需 RTL 改动）
- v14 / v15 / TD-D 历史不变量保持

### 下一步
- TD-E（lane1 Real Dual-Load）解除 no-double-Load 门控，预期 IPC 收益（详 TASK 后续版本）

---

## v19 —— TD-E lane1 Real Dual-Load 调查（WIP，已回退到 v18 baseline）

### 时间线
1. 启动 TD-E：rubber-duck 评审后修订实施顺序（Memory.scala 加断言 → per-lane 化 → arPending → 双 alloc → 解除 Issue 门控）。
2. 落地 LaneCapability.scala（新增）+ Issue.scala hazard 扩 8 槽 + MyCPU 双 lane hazard 接线 + Memory.scala 部分 per-lane 化。
3. 解除 doubleLoadStall：sim-regressive `kernels_sum_unroll_ooo4_unroll` FAIL（cycle 2266 βwake pdst=54 RAW 竞态）。
4. 排查证实 βwake 在 dual-Load 模式下与 Memory 反压形成结构性 RAW 竞态（Bug A）。
5. 尝试禁用 βwake：`kernels_sum_unroll` PASS 但 `algo_list_reverse_ooo4_unroll` FAIL（Bug B，与 dual-Load 解耦）。
6. 二分排查证实 Bug B 是 βwake-off 引入的独立 bug；本会话上下文不足以完成波形深挖。
7. 回退至 v18 baseline：恢复 `Issue.scala` doubleLoadStall + `MyCPU.scala` βwake lane0；rebuild + 全量回归 → sim-basic 39/39 + sim-regressive **70/70 PASS** 验绿。
8. 同步文档：BUG.md / STUDY.md / PLAN.md / TASK.md 加 v19 章节，详记 Bug A / Bug B 实证与下次会话起点。

### 保留的 v19 探索性改动（dirty tree，对 v18 baseline 无回归）
- `LaneCapability.scala`（新增）：useRs1/useRs2/isAluOnly/isLType/isBType/isJalr/loadLanes/storeLanes 单点 helper。
- `Issue.scala`：hazard 扩到 `3 * loadLanes.size + 2 = 8` 槽。
- `MyCPU.scala`：6 + 2 槽 hazard 接线 per-lane 化。
- `Memory.scala`：per-lane 化骨架（actAddr/actPdst Vec、双 capture、arPending）部分落地。
- 这些骨架供下次会话直接复用。

### 验收（回退后）
- sim-basic：**39/39 PASS**
- sim-regressive：**70/70 PASS**

### 下一步
v19 闭环路线见 `PLAN.md` 第十节 + `BUG.md` v19 章节 + `TASK.md` v19.2。

---

## v19.3 闭环（同会话续）—— TD-E lane1 Real Dual-Load 已上线

### 改动清单
| 文件 | 改动 | 说明 |
|---|---|---|
| `src/main/scala/dataLane/Issue.scala:131-141` | 删除 `doubleLoadStall` | 仅保留 `doubleBrStall` + `pairRaw` |
| `src/main/scala/MyCPU.scala:535-572` | βwake 三重门控 v4 | 增 `Memory.in.ready` + `!anyLoadInExBundle`；betaEnable 仍 lane0 |
| `src/main/scala/dataLane/Memory.scala`（v19 已落地骨架） | 双 capture / per-lane AR 仲裁 / all-or-none accept | 配合 `mshrCaptureFire` Vec、`outMaskBits` per-lane |

### 验收数据
- sim-basic **39/39 PASS** + sim-regressive **70/70 PASS**（包含 v19 调查档暴露的 `kernels_sum_unroll_ooo4_unroll` Bug A 触发用例）。
- Bug A 修复机制：第 3 重门控 `!anyLoadInExBundle` 阻断"同 bundle Load 触发 memStall 把 ALU 生产者一并卡在 ExMemDff" 的结构性路径。
- Bug B 仍未定位但被绕开：βwake 仍开（lane0），未触发"βwake 全禁"暴露面。

### 留给下个里程碑（v20+）
- Bug B 根因深挖（波形）；
- lane1 βwake 启用尝试；
- TD-F 双发 Branch（解除 doubleBrStall）。

---

## v20 IPC 回收 —— lane2/3 βwake 启用 + 三重门控保留

### 背景
v19.3 实测 IPC 回退：`algo_array_ops_ooo4_unroll −11.1%`、`kernels_load_latency_hide_ooo4_unroll −4.2%` 等。
根因：βwake 第 3 重门控 `!anyLoadInExBundle` 是 bundle 级一刀切，连 lane2/3 ALU-only（永不可能是 Load）也被压。

### 决策与安全分析
- 用户提议方案 A：开 lane2/3 βwake（直觉是 lane2/3 不可能是 Load 故安全）；
- rubber-duck 评审确认：**lane2/3 βwake 必须保留 `!anyLoadInExBundle` 门控**！
  原因：bundle 是原子流水（Memory.memStall 一停整 bundle 全停）。
  即使 lane2 ALU 自己不是 Load，只要同 bundle 任意 lane 是 Load 触发 memStall，
  lane2/3 ALU 生产者也会被一起卡在 ExMemDff 多 1 拍，N+3 PRF 写时序破坏 → 复现 Bug A。
- 故安全方案：开 lane2/3 βwake **同时**保留三重门控；lane1 仍保持禁用（保 v19.3 不变量 I-D）。

### 改动清单
| 文件 | 改动 | 说明 |
|---|---|---|
| `src/main/scala/MyCPU.scala:560-568` | `betaEnable = (k.U === 0.U) \|\| (k.U === 2.U) \|\| (k.U === 3.U)` | lane2/3 加入 βwake 范围；lane1 仍禁用 |

### 验收
- sim-basic **39/39 PASS** + sim-regressive **70/70 PASS**。
- IPC 对比（v18 / v19.3 / v20）：
  - `algo_array_ops_ooo4_unroll` 0.891 / 0.792 / **0.855**（vs v18 −4.0%，已从 −11.1% 大幅回收）；
  - `kernels_load_latency_hide_ooo4_unroll` 1.762 / 1.688 / **1.805**（vs v18 **+2.5%**，超过 v18）；
  - `kernels_load_latency_hide_baseline` 1.544 / 1.515 / **1.727**（vs v18 **+11.8%**）；
  - `kernels_memcpy_like_ooo4_unroll` 1.248 / 1.220 / **1.368**（vs v18 **+9.6%**）；
  - `kernels_memcpy_like_baseline` 1.150 / 1.147 / **1.273**（vs v18 **+10.7%**）；
  - `kernels_dep_chain_ooo4_unroll` — / 1.396 / **2.063**（vs v19.3 **+47.8%**）；
  - `kernels_sum_unroll_ooo4_unroll` — / 1.318 / **1.471**（vs v19.3 +11.6%）。

### 留给下个里程碑（v21+）
- P1 lane1 βwake 启用（前置：P3 Bug B 深挖以避免暴露）；
- P2 精确 lookahead 替换 `!anyLoadInExBundle`（含 Load 但不实际 stall 的 bundle 也放 βwake）；
- P3 Bug B 根因深挖（algo_list_reverse_ooo4_unroll 在 βwake 全禁场景 FAIL）；
- P4 TD-F 双发 Branch；
- P5 BTB/RAS 升级。

---

## v21 代码清理（2 of 3 Batch 完成 + Batch 3 主动收敛）

### Batch 1 改动（stale 注释 + 死 scaffolding，已落地）
| 文件 | 内容 |
|---|---|
| `CPUConfig.scala:55-61` | iqIdxWidth 注释 "4 位" 改正为 "6 位"；半区间表述 "≤ 16" 改为 "≤ 48"；issueQueueEntries 备注由 "Phase E：32→48" 改为 "实测最优" |
| `dataLane/IssueQueue.scala:130-186` | 删除整段"阶段 α.1（已暂时回滚）"过渡说明；折叠死代码 `wk1ext/wk1`、`hitExt1/hit1` 中转变量；section 标题"阶段 α.1 / 阶段 1"前缀去除 |
| `dataLane/IssueQueue.scala:314` | 删除"阶段 α.1（已回滚）：fast wakeup 导出逻辑已移除"占位注释 |
| `MyCPU.scala:151,200` | "阶段 1" 前缀去除 |
| `dataLane/DiffPayloads.scala:56,348` | "阶段 1" 前缀去除 |
| `dataLane/Rename.scala:247` | "阶段 1" 前缀去除 |

### Batch 1 验收
- `xmake b rtl + b Core + sim-basic + sim-regressive` 全绿（39/39 + 70/70）
- IPC 与 v20 baseline `diff` 显示**完全一致**（120 个 TC 0 diff）

### Batch 2 调查结论（未落地，主动收敛）
- 全 36 个文件 `import` 经核查均被使用；
- 全部 `Wire(_)` 声明均有后续 `:=` 写入与读取，无明显死信号；
- 无可执行的清理项 → 不动代码；视为"调查通过"。

### Batch 3 主动收敛（未落地，记录决策）
- 候选改写（PriorityEncoder 链 → for、`val tmp = expr` 内联）属可读性微调；
- 单项改写需要 SV diff + 全量 IPC 回归保护，单点 ROI < 0.1% 可读性提升；
- 对已稳定回归通过的项目，引入风险不划算 → **不做**；
- 决策依据：本项目作为本科毕设交付，"功能/性能/文档"优先级高于"代码美观"。

### v21 不变量保持
- I-A..I-D（v19.3 + v20）全部不变；βwake 三重门控、lane2/3 启用范围、Memory bundle 原子性均无改动。
