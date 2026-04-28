# sim-regressive BUG 调研登记（首次发现）

> 本文件记录 sim-regressive 56 用例集合中**预先存在**（非本次会话引入）的失败用例。
> 此前所有 LOG / STUDY / PLAN / TASK 文档仅验证了 sim-all 41/41 PASS，sim-regressive
> 从未在文档中体现。本次会话首次跑全 sim-regressive 才发现下列问题。

## 基线（TD-C 代码状态，未做任何修改）

- `SIM=verilator xmake r sim-basic`：**39/39 PASS** ✅
- `SIM=verilator xmake r sim-regressive`：
  - 通过：36/56
  - 失败：16/56
  - 耗时过长（>60s，按用户指示忽略）：4/56 —— `kernels_indirect_call_*`

## 失败用例清单（16 例，按"暴露 commit 数"升序便于优先调试）

| # | 用例 | 暴露 commit | 失败模式 |
|---|---|---|---|
| 1 | kernels_branch_reduce_debug | 1091 | difftest 不一致（最早暴露，应优先调试） |
| 2 | coverage_test_build_debug | 2818 | difftest 不一致 |
| 3 | kernels_sum_unroll_ooo4_unroll | 3199 | difftest 不一致 |
| 4 | algo_list_reverse_ooo4_unroll | 9847 | difftest 不一致 |
| 5 | algo_list_dedup_ooo4_no_libcall | 10540 | difftest 不一致 |
| 6 | kernels_load_latency_hide_ooo4 | 10548 | difftest 不一致 |
| 7 | kernels_load_latency_hide_ooo4_no_libcall | (未细究) | difftest 不一致 |
| 8 | algo_list_dedup_ooo4 | (未细究) | difftest 不一致 |
| 9 | algo_array_ops_ooo4_no_libcall | (未细究) | difftest 不一致 |
| 10 | algo_bst_ops_ooo4 | (未细究) | difftest 不一致 |
| 11 | algo_bst_ops_ooo4_no_libcall | (未细究) | difftest 不一致 |
| 12 | kernels_branch_reduce_ooo4_unroll | (未细究) | difftest 不一致 |
| 13 | kernels_dep_chain_ooo4 | 20513 | difftest 不一致（晚期暴露） |
| 14 | kernels_dep_chain_ooo4_no_libcall | (未细究) | difftest 不一致 |
| 15 | kernels_dep_chain_ooo4_unroll | (未细究) | difftest 不一致 |
| 16 | algo_array_ops_ooo4 | 19522 hang | **死锁**：FB=29/16 (溢出) IQ/ROB 全空，10000 拍无 commit |

## 失败模式分类

### 类型 A：流水线死锁（1/16）—— `algo_array_ops_ooo4`
- **观测**：FB 计数器疑似溢出（29/16），后端（IQ/ROB/SB/SQ）全空，前端到 Rename/Dispatch 之间出现死锁；
- **疑似根因方向**：FB write/read 指针在某些回滚场景下与计数寄存器失步；或 Rename 端某个 `*.ready` 永久 false。

### 类型 B：difftest 状态不一致（15/16）
- **观测**：PC 与黄金模型一致，但寄存器/内存某字段与 NEMU 不一致；
- **典型暴露点**：Commit #1k ~ #20k，大量 OoO 边界已被触发；
- **疑似根因方向**：
  - lane1/lane2/lane3 写回的某个边界条件（rollback 时 sbWrite 抑制、mshrComplete 与 commit 撞 pdst、refresh 写口仲裁等）；
  - 长 store buffer 队列下 SB↔SQ 转交边界；
  - mispredict 抑制后 lane1 自身 Branch 的边界（TD-C MVP 仲裁）；
  - 双 Store / 双 Load 同拍发射的 SB 索引 / SQ 入队边界。

## 修复进度（按修复时间倒序）

（待开始）


## 调研进展（本会话）

### kernels_branch_reduce_debug 首例细化

- **失败点**：`Cycle 0x28d` `Commit #1091` lane2 `PC=0x3f0`
  指令 `00050593 = addi x11, x10, 0`（即 `mv x11, x10`）
- **数据对比**：
  - RTL: `RD=x11 REGWDATA=0x000102ec`（疑似某个早期数据区指针）
  - REF: `RD=x11 REGWDATA=0xecb82367`（正确：刚才 lane0 commit 写 x10 的值）
- **直接征兆**：lane2 在 ReadReg 时读到了**陈旧的 x10**——不是最新 commit 的 0xecb82367，
  而是一个更老的指针型值 0x102ec。
- **可能根因（未验证）**：
  - (a) Rename bypass 对 lane>=2 同拍依赖前老 lane 的 pdst 时映射出错；
  - (b) ReadyTable 把 x10 的 pdst 提前置 ready，但 PRF 实际写未发生（β/γ wake 时序 BUG）；
  - (c) Issue 推测唤醒（spec wake）链让 lane2 在生产者 Execute 同拍发射，但 N² 前递路径
        没把生产者 outL.data 接到 lane2 的 actual_rdata1（Phase C 实现似乎正确，需波形验证）；
  - (d) 分支误预测恢复后某个 RAT/PRF 映射没回滚到位（推测度更低）。

### 已检视的源码路径（无明显 BUG，需波形佐证）

- `Rename.scala` line 102~125：rename bypass 对同拍 j<i 的 pdst 转发逻辑正确；
- `RAT.scala`：单端口写、x0 钉住、批量 recover 逻辑正常；
- `ReadyTable.scala`：set/clear 优先级、recover 与 refresh 合流正确；
- `IssueQueue.scala` line 140~186：入队当拍读 RT + wakeup 旁路、α.1 fast wakeup 已禁用；
- `IssueQueue.scala` line 260~322：spec wake 链式（laneSpecValid/Pdst）+ olderAllFire 门控；
- `Execute.scala` line 86~150 + 224~234：N² 同拍前递（laneFwdValid/Pdst/Data）；
- `MyCPU.scala` line 411~505：Refresh effRef* + PRF 写 + ReadyTable.readyVen + IQ.wakeup 多 lane。

### 工具链阻塞

- 已搭建波形工作区 `.waveform-debug/kbr_x10/`（vcd 链入 + main.lua 已写脚本扫描
  `cycle 0x280..0x290` 内 `uReadReg.out_bits_lanes_*_pc==0x3f0` 那拍的 psrc1/src1Data
  与 PRF 4 个写口的 pdst/wdata）；
- **`./wdebug run` 失败**：`wave_vpi_main` 加载共享库 `libwave_vpi_wellen_impl.so`
  报 *cannot open shared object file*；该库在 `/home/litian/Documents/verilua-x64-ubuntu-22.04/`
  全树**不存在**——verilua 安装缺失 vcd 后端二进制。
- 后果：本会话**无法**跑 wave-debug full-mode 完成 first-bad-cycle 定位。

### 后续策略候选

1. 修复 verilua 安装（补 `libwave_vpi_wellen_impl.so`），或改用 fst 格式抓波；
2. 切回 verilator 自身的 `+trace` + GTKWave 离线人工读波形；
3. 在 Chisel 端加 `printf` 仪表（在 Rename / Issue / Execute 关键节点打印 lane2 的
   pdst/psrc1/src1Data），用日志替代波形；
4. 暂搁 16 BUG，先做 TD-D/E/F——但鉴于这些 BUG 是预先存在的状态偏差，TD-D/E/F 大概率
   仍踩同一根因，IPC 收益也无法测量。

### 开放问题

- 16 例失败是否同根因？kernels_branch_reduce_debug 的"陈旧寄存器读"是否能批量解释其余 15 例？
  需波形确认后才能下定论。

---

## ✅ 修复进度（本会话）

### 修复 #1（2025 本次会话）：ReadyTable 分支快照同拍 busyVen 旁路缺失

**根因**（通过 wave-debug `kbr_x10` 工作区 + `main.lua` 全程扫描定位）：
- `Rename.scala` 为 RAT 快照精心实现了 `postRenameRAT1/2`——叠加同拍内更老 lane 的 `wen` 形成"分支可见的真实 RAT 状态"；
- 但 ReadyTable 的快照路径 `MyCPU.scala`：`uBCT.saveDataIn(*).readyTb := uReadyTable.io.snapData` 直接吃 `table` 寄存器原值；
- 当 **分支与一个产生新 pdst=P 的更老 lane 在同一拍 Rename** 时，分支快照里 P 仍是 ready=1（陈旧），分支误预测恢复时
  `ReadyTable[P] := 1`，但 P 对应的生产者其实仍在飞行中，未来才写 PRF；
- 消费者读 PRF[P] 拿到完全无关的旧值（典型如 kbr_x10 案例：`x10` 实际是更早某条指令的产物 `0x102ec`，而非真正最新值 `0xecb82367`）。

**关键波形证据**（kernels_branch_reduce_debug，cycle 0x273 RECOVER）：
- cy 0x26a：Rename busyVen[1]→addr=0x27（PC=0x104 是新 x10 生产者）
- cy 0x273：ReadyTable RECOVER（更年轻的分支误预测）
- cy 0x279：PC=0x3f0 入队，readyQuery raddr[0]=0x27 → **rdata=1**（错！）
- cy 0x27b：lane2 ReadReg 读 PRF[0x27]=0x102ec（陈旧）
- cy 0x28b：PC=0x104 真正 Refresh，PRF[0x27] 才被写为 0xecb82367（晚 16 拍）

**修复**：
1. `Rename.scala` 新增 IO `readyTbSnapIn`（接 `uReadyTable.io.snapData`）和 `ckptReadyTb.{postRename1,postRename2}`，
   逻辑与 `postRenameRAT1/2` 完全镜像——`ckptLaneMask{1,2}` 对齐叠加 lane 的 busyVen，把 pdst 对应位清零；
2. `MyCPU.scala` 把 `uBCT.saveDataIn(0/1).readyTb` 改接 `uRename.ckptReadyTb.postRename1/2`。

**回归结果**（修复前 → 修复后）：
- sim-basic：39/39 PASS（无回归）
- sim-regressive：**16 失败 → 2 失败**（14 例同根因被一次性收割：
  kernels_branch_reduce_debug、coverage_test_build_debug、kernels_sum_unroll_ooo4_unroll、
  algo_list_reverse_ooo4_unroll、algo_list_dedup_ooo4_no_libcall、kernels_load_latency_hide_ooo4 等 14 例）

### ❌ 仍未解决（2 例，pre-existing 独立 BUG）

| # | 用例 | 失败模式 | 备注 |
|---|---|---|---|
| 16 | algo_array_ops_ooo4 | 死锁：FB=29/16，IQ/ROB 全空，19522 commits 后 10000 拍无 commit | 与 BUG.md 原 #16 同症状，预先存在 |
| 9  | algo_array_ops_ooo4_no_libcall | 死锁：同上，停在同一 Cycle 0x6ae9 / Commit 19522 | 与 #16 似为同根因（应一并修复） |

**疑似根因方向**（待下一会话调查）：
- FB write/read 指针在某些回滚场景下与计数寄存器失步，FB=29 实为 wraparound/overflow 计数器问题；
- 或 Rename `canRename` 在特定 corner 永久 false（ROB=0/IQ=0/SB=0 但 FB 满 → 反压来源待查）；
- 建议先观察 `coreCpu.uFetchBuffer.{count, headPtr, tailPtr}` 与 `coreCpu.uRename.{canRename, in.ready, in.valid}` 在 Cycle 0x6ae0 附近的演化。

---

## v15 修复 #2：FetchBuffer 截断 ≥3 个预测分支（algo_array_ops_ooo4 死锁根因）

### 现象
- `algo_array_ops_ooo4` 与 `algo_array_ops_ooo4_no_libcall` 在 commit ≈ 19522 后停滞 10000+ 拍无 commit；
- 死锁时 BCT `head=9 tail=1 count=8`（满），FB=29/16，IQ/ROB 空；
- Rename `canSave(0)=false` 永久成立 → 取指通路反压死锁。

### 根因
- BCT `saveW=2` 是硬约束（每拍最多保存 2 个 checkpoint）；
- `Rename.scala` 用 `PriorityEncoderOH` 仅给前 2 个 bType/JALR 分支分配 checkpoint，第 3+ 个分支 `hasCheckpoint=false / checkpointIdx=0`，但仍被 rename 入 ROB；
- 该分支日后 mispredict 时，Memory 用 `ckptIdx=0` 作 `recoverIdx` 触发 BCT "幻影 recover"——把 `tail` 设为非法值，`count` 在后续 free 中失序，最终 BCT 永久 full → 死锁。

### 修复
- `FetchBuffer.scala` 出队侧（v15）：
  - 用指令 opcode 检测 4-lane 中 bType（`0x63`）/ JALR（`0x67`）；
  - 当出现 ≥3 个预测分支时，把出队数量 `truncatedDeqCount` 截断到第 2 个分支所在 lane（含）；
  - 通过 `truncatedDeqCount` 同步覆盖 `deq.bits(i).valid` 与 `head` 推进；
  - 第 3+ 分支留在 buffer，下拍出队时位于新 4-lane 窗口低位，自然成为下拍的第 1/2 个分支可正常保存 checkpoint。
- `BranchCheckpointTable.scala`：保留 `recoverIdxInRange` 作 defense-in-depth；
- `MyCPU.scala`：`memRedirectValid` 与 BCT.recoverIdxInRange 联动（正常路径下 inRange 永远 true，gate 为 no-op）。

### 验证
- `xmake b rtl` ✅；`xmake b Core` ✅；
- sim-basic：**39/39 PASS**（无回归）；
- sim-regressive：**52/56 PASS**（4 例 `kernels_indirect_call_*` 按指示忽略）；
- 两例 `algo_array_ops_ooo4` / `..._no_libcall` 同步修复。

### 至此
- 所有 sim-regressive **可修复用例**已全部 PASS。

---

## v16 调研：`kernels_indirect_call_debug` 仿真超时（首次系统性分析）

> 触发：用户指出该用例仿真时间显著长于其他程序，但程序本身**不是死循环**——
> 我们之前简单地按 v14/v15 指示把 `kernels_indirect_call_*` 全部归为"用例编写不合理 / 忽略"，
> 实际并未深查根因。本节为 v16 首次正式立案。

### 1. 现象量化（来自 `difftest/build/sim-data/`）

| 用例 | CSV 行数 ≈ 仿真拍数 | 末拍 commits | 末拍 IPC | 备注 |
|---|---|---|---|---|
| `kernels_indirect_call_baseline` | 115 | 40 | 0.33 | 程序 ~40 条指令，正常 halt |
| `kernels_indirect_call_ooo4` | 115 | ~ | ~ | 同上 |
| `kernels_indirect_call_ooo4_no_libcall` | 115 | ~ | ~ | 同上 |
| `kernels_indirect_call_ooo4_unroll` | 115 | ~ | ~ | 同上 |
| **`kernels_indirect_call_debug`** | **1 395 805** | **1 987 103** | **1.4236** | 仿真器到达拍数上限仍未 halt |

**关键观测**：debug 版本**既没有死锁也没有 IPC 崩塌**——IPC 全程稳定在 ~1.42，
commits 单调递增到约 200 万条；仿真因撞 max-cycle 截止。
即：CPU 在持续高效率地"提交真实指令"，但程序就是不结束。

### 2. 用例区别（dump 对比）

- 优化版本（baseline / ooo4 / ooo4_no_libcall / ooo4_unroll）：编译器把 `run_indirect`
  的间接调用全部内联/常量传播掉了，整段 main 退化成 ~10 条 ALU + sw + ecall，dump 仅 72 行；
- **debug 版本（-O0 风格）**：保留了完整的 indirect-call 控制流，dump 223 行，
  关键热点在 `run_indirect`（PC 0x17c..0x1f4）：
  ```
  0x1b8  lw   a5, 0(a5)        # 从 OPS 表加载函数指针（典型 load-of-pointer）
  0x1bc  lw   a1, -24(s0)
  0x1c0  lw   a0, -20(s0)
  0x1c4  jalr ra, 0(a5)        # 紧跟一条 indirect-JALR，rs1=a5
  ...
  0x1d4  sw   a5, -24(s0)      # i++（栈帧上的循环变量）
  0x1d8  lw   a5, -24(s0)      # 重新读 i（-O0 风格，每次都过栈）
  0x1dc  bge  zero, a5, 0x1a0  # 循环回边
  ```
  以及 main / `_start` 中三段 `addi a*, a*, 4 ; bgeu / bge ; jal zero, <back>` 形式的
  小循环（栈帧-地址变量加 4 后比较），是典型的"短回路 + 频繁过栈写读"模式。

### 3. 根因假说（按可能性排序，全部待波形佐证）

#### H1（首选）：indirect-JALR rs1 的 byPass 缺漏 → 跳到错误目标后陷入旁路热代码

证据：
- 0x1b8 的 lw 与 0x1c4 的 jalr 之间隔 2 条 lw（相同 lane 的 Memory 段，串行 ack）；
- TD-D 后 `mshrComplete` 已是 Vec(2)，但 PRF 的写口数与 ReadyTable.refresh 写口
  仲裁在"同拍 ack 一个 Load 完成 + 提交一个 Branch"时存在窄路径；
- 若 jalr 在 ReadReg 阶段拿到的 a5 是 **Load 写回前的旧 PRF 值**（旁路链漏了某一拍），
  目标是一个无效 PC，但该 PC 落在 `_start`/`run_indirect` 内部某个合法指令边界上，
  CPU 会在错误地址段持续取指 → 撞回某条 `jal zero, <back>` → 形成"假性活循环"，
  而 difftest 不开（regressive 端用 ecall halt 比对）只看 cycle 上限，所以表现为超时。
- 与 v14 修的 ReadyTable 快照旁路问题（`kbr_x10`）症型相似：消费者读到陈旧 PRF；
  v14 修的是分支快照路径，**indirect-JALR 走的是非分支快照下的常规旁路**，覆盖度不同。

应优先验证：cycle X（首条 0x1c4 commit 拍）`uReadReg.out_bits_lanes_*_pc==0x1c4`
对应的 `psrc1 / src1Data` 与 PRF[psrc1] 真实值的关系；若 src1Data ≠ 期望（=0x9c/0xd4/0x10c/0x144 之一），命中 H1。

#### H2（次之）：BTB 对 indirect-JALR 训练后**反复命中错误目标**

证据：
- `BTB.scala` 直接映射 entries=32，`update_valid` 在 Execute 阶段无条件覆盖；
- `MyCPU.scala:310` 用 `!memRedirectValid` 抑制 BTB 写——但若 mispredict 已经
  在 Execute 自身那一拍解析了 JALR，写口仍会被同拍 mispredict 抑制掉，**导致
  BTB 永远学不到正确目标**，每次该 JALR 都 cold miss → 误预测 → redirect。
- 误预测本身不会让指令流偏离正确路径（架构提交仍走真实目标），但每次 JALR
  会浪费 ~5 拍。**单 JALR 的 5 拍/次远不到 1.4M 拍量级**，所以 H2 单独无法解释超时；
  但若与 H1 叠加，可形成"误预测 → redirect 到错误旁路 PC → 在错误代码区不
  退出"的复合现象。

#### H3：栈帧 store-to-load 转发漏情况下，循环变量 `i` 永远被读为旧值

证据：
- 0x1d4 `sw a5,-24(s0)` 紧跟 0x1d8 `lw a5,-24(s0)`，栈地址完全相同；
- 若 SQ→Load 转发对"刚 enq 还未 commit 的 store"在某种边界（比如 SB 满 / 同拍
  双 Store 旁路）漏发，0x1d8 会从 DRAM 读到 0；`bge zero, 0` 永远 taken → 真正的
  架构级死循环，每拍 IPC ≈ 4，2M 提交完全可解释。
- 与 _start 三段 4-byte 步进循环也吻合（同样是栈/地址变量先 sw 再 lw）。
- **此假说会被 difftest 立刻发现**：但 v14/v15 时该用例被划入 timeout 而非
  difftest mismatch，所以可能架构提交仍是正确的——这反而**降低**了 H3 的概率：
  如果架构 i 永远 0，commit_pc 应反复落在 0x1a0..0x1d8 内，nemu 一秒就发现失配。
  除非 difftest 在 timeout 路径下不写 mismatch 报告（待验证）。

### 4. 待办（写入 TASK.md / PLAN.md）

按"低成本快速排除 → 再上波形"次序：

T-INDIR-1 抓 architecturally-committed PC trace（前 1k commits 与 commits ≈ 100k 段）：
   - 若 PC 长期复读 `_start` / `run_indirect` 一段，→ H1（错误旁路区）；
   - 若 PC 卡在 `0x1a0..0x1dc` 循环内，→ H3（store-to-load 漏转发）；
   - 若 PC 走完 main 但卡在 ecall 之前的某段，→ H1 的 op_X 内部错误地址。

T-INDIR-2 复现单测：`SIM=verilator TC=kernels_indirect_call_debug DUMP=1 xmake r Core`，
   抓 `.vcd`，按 wave-debug full-mode 流程在 cycle ≈ 100 内定位首次 PC 偏离正确轨迹的拍。

T-INDIR-3 根据首拍证据落锤到 H1/H2/H3 之一并修复；与 TD-E（real dual-Load）排序：
   - 若是 H3（转发漏） → **优先于 TD-E**，因为 TD-E 双 Load 会进一步加压 SQ 转发；
   - 若是 H1（JALR rs1 旁路） → 也优先于 TD-E（同样涉及 byPass 通路扩张）；
   - 若是 H2（BTB 写口被 mispredict 抑制） → 与 TD-E 解耦，可并行处理。

### 5. 风险与不确定性

- 当前仅有 CSV + dump 两个静态证据；未跑过 single-test 也未开过波形——所有结论均为假说；
- 本会话先**不**改 RTL，仅落档。下一步需要用户授权进行：
  (a) 单跑该测试抓 `.vcd`；
  (b) 在 Chisel 加 `printf` 打印 0x1c4 commit 那拍的 src1 与目标 PC；
  (c) 评估是否暂停 TD-E、先解此 BUG。

---

## v17 实证：根因锁定 —— **平台未把 .rodata 加载到 DRAM**

### 1. 关键证据（来自 9.8M 行 commit 日志 `/tmp/indir_run.log`）

实跑 `SIM=verilator TC=kernels_indirect_call_debug TIMEOUT=80 xmake r Core`，仿真到 commit #2,672,939、cycle 0x1d2258，IPC≈1.40 仍在持续推进、**difftest 全程 PASS 无任何 mismatch**。从日志做 PC 直方图：

| PC 段 | 含义 | 提交次数 |
|---|---|---|
| 0x0..0x90 | _start 全段（重复 31821 次） | 31821 |
| 0x2c0..0x2d8 | main 入口至首条 jalr 调 run_indirect | 31821 |
| 0x17c..0x1c4 | run_indirect 入口至 `jalr ra, 0(a5)` | 31820 |
| **0x1c8** | run_indirect 中 jalr **返回点** | **0** |
| **0x9c / 0xd4 / 0x10c / 0x144** | op_add/op_sub/op_xor/op_or | **0** |
| 0x1f8（run_direct） | run_direct 入口 | 0 |
| 0x318（main 返回路径） | lw ra,12(sp) | 0 |

**含义**：每次 main 重新进入，调用 run_indirect 中 `jalr ra, 0(a5)` (PC=0x1c4) 后，**永远不进入任何 op_X 函数体，也永远不回到 0x1c8**，而是跳到 PC=0 重启整个程序。run_direct、main 后半段、ecall 永远不被执行。

### 2. 关键拐点：rodata→OPS 拷贝写入的全是 0

从 PC=0x28（`sw t0, 0(a1)` —— `_start` 中 OPS 表的 store）实际值：

```
[Commit #10]  PC=28 RAMWADDR=10000 RAMWDATA=0 RAMWMASK=2
[Commit #16]  PC=28 RAMWADDR=10004 RAMWDATA=0 RAMWMASK=2
[Commit #22]  PC=28 RAMWADDR=10008 RAMWDATA=0 RAMWMASK=2
[Commit #28]  PC=28 RAMWADDR=1000c RAMWDATA=0 RAMWMASK=2
```

应当写入 `0x9c, 0xd4, 0x10c, 0x144`（4 个函数指针），实际全是 **0**。
追溯到 PC=0x24（`lw t0, 0(a0)` 从 `a0=0x328 (__rodata_load_start)` 读取）—— RTL 也读到 0。

### 3. 根因（确诊）

**reference Lua 模拟器（`difftest/src/emu.lua:225`）**：
```lua
self.imem = file:read("*a")  -- 整 .bin 加载到 imem（指令内存）
self.dmem = {}                 -- 数据内存初始化为空 sparse table
```
`emu:raw_read(addr)` 在地址未写过时返回 0（line 103: `or 0`）。**.rodata 字节物理上确实在 .bin 内（0x328..0x337 = `9c 00 00 00 d4 00 00 00 0c 01 00 00 44 01 00 00`），但仅落在 imem，从未拷贝到 dmem。**

**RTL 侧**：`difftest/xmake.lua` 仅把 `.bin → .hex` 通过 `$readmemh` 装入 **IROM**（指令 ROM）；DRAM 启动时全 0。

**测试程序 `_start` 用 `lw t0, 0(__rodata_load_start)` 期望从地址 0x328 读到函数指针** —— 该地址在 IROM 里有正确数据，但**数据 load 路径 RTL 走 DRAM、ref 走 dmem，二者都返回 0**。OPS 表被填 0 → `lw a5, 0(OPS[0])` 得 0 → `jalr ra, 0(a5)` 跳 PC=0 → **死循环重启**。

`-O3` / `-Os` 优化版本中编译器把这 4 个常量直接折叠为立即数 / 直接调用，**没有 rodata load**，所以躲过此坑（CSV 显示这些版本 115 行就 halt）。`_debug` 用 `-O0` 保留了 .rodata，于是暴露此设计缺陷。

### 4. 这是哪类问题？

**主因：difftest 平台框架的"指令/数据内存分离"模型缺陷**。表现层是测试用例自身的 .rodata 引用未受平台支持。
归类：**difftest 框架问题**（不是 RTL bug，不是测试用例 bug；测试用例只是把暗坑挖出来）。

### 5. 副作用发现（独立 bug，不修不影响本案）

`difftest/src/main.lua:268-274` mismatch 比对逻辑：
```lua
local mismatch = (rtl_pc ~= ref_pc)
if ref_reg_wen == 1 then
    mismatch = (rtl_reg_wen ~= 1) or (rtl_reg_waddr ~= ref_reg_waddr) or (rtl_reg_wdata ~= ref_reg_wdata)  -- ★ 覆盖了 PC check
end
if ref_ram_wen == 1 then
    mismatch = (rtl_ram_wen ~= 1) or ...   -- ★ 又覆盖一次
end
```
对任一写寄存器或写内存的指令，**PC 不匹配会被静默吞掉**。本案中 ref 与 DUT 走相同跑飞轨迹，所以即便修了这个 bug 也不会触发 mismatch；但它是真 bug，建议改为 `mismatch = mismatch or (...)`。

### 6. 修复方案候选

- **F1（首选）**：在 `difftest/src/emu.lua` 的 `init_program` 里把 `self.imem` 内容拷一份到 `self.dmem`；同时让 RTL 端 DRAM 启动时也用 `$readmemh` 预载 .bin 内容（与 IROM 共享同一份数据镜像）。语义上等价于"统一物理内存"模型，与一般 RV32I 平台一致。
- **F2**：保留分离内存模型，但要求所有测试用例链接脚本把 .rodata 放到 DRAM 起始地址（>=0x10000），并由 `_start` 自己 `memcpy` ——但要求重新写链接脚本与 ld script 适配，影响所有测试用例。
- **F3（绕过）**：把 `kernels_indirect_call_debug` 从 regressive 集合移除或加 cycle 上限标注，承认平台不支持 -O0 含 rodata 引用的程序。

推荐 **F1**：改动局部、影响面可控；同时 fix 掉副作用 `main.lua` 的 mismatch 覆盖 bug。

### 7. 后续 TASK

- TD-INDIR-A：emu.lua 改 dmem 预载 .bin
- TD-INDIR-B：RTL DRAM 模块（DRAM_AXIInterface 或对应 sv 文件）启动时 `$readmemh` 预载 .bin
- TD-INDIR-C：main.lua mismatch 覆盖 bug 修正
- TD-INDIR-D：跑 `kernels_indirect_call_debug` 验证 ecall halt；跑全 regressive 回归不退化

---

## v18 验收 —— TD-INDIR 完成

### 修复确认
- TD-INDIR-A（emu.lua dmem 预载 .bin）✅
- TD-INDIR-B（mem_65536x32.sv 注入 $readmemh + dram.hex 生成）✅
- TD-INDIR-C（main.lua mismatch `=` → `or`）✅

### 数据
| 用例 | v17 | v18 |
|---|---|---|
| kernels_indirect_call_debug | 1.4M+ cycles 不收敛 | 345 cycles / 168 commits ECALL halt |
| sim-basic | 39/39 | 39/39 |
| sim-regressive | 52/56（4 timeout 忽略） | **70/70** |

### 复盘要点
1. v16 的 H1/H2/H3（RTL byPass / BTB / SQ）全部排除：difftest 全程 PASS 是因为 ref 与 DUT 共用同一个错误内存模型互相验证，不是 RTL 正确。今后"长仿真 + IPC 稳定 + 无 mismatch"应优先排查平台/参考模型一致性。
2. mismatch 累加缺陷（main.lua）单独是个独立 bug，会让 reg-write 之后再赋值 ram_wen 分支时把 PC mismatch 误判为 PC 一致——本案未触发，但今后多检出力下值得保留 `or` 写法。
3. -O0 编译与 -O2 行为差异提醒：测试用例集应同时覆盖两种优化级别，常量折叠会掩盖平台缺陷。

---

## v19 调查（TD-E lane1 Real Dual-Load WIP）

> 状态：未合入。dual-Load 启用暴露两类独立 bug，根因调查需深波形跟踪，已回退至 v18 baseline 70/70 PASS。
> 完整接续指南：见 `PLAN.md` 第十节。

### Bug A — βwake × Memory 反压结构性 RAW 竞态（dual-Load 触发）

- **触发用例**：`kernels_sum_unroll_ooo4_unroll`
- **现场**：cycle 2266 βwake 广播 pdst=54；cycle 2270 才 Refresh 写 PRF；消费者 `add a0,a4,t6` cycle 2268 RR 读 PRF[54] 拿到旧值，wdata=0x48009ddf vs REF=0x3f7a8c1c。
- **根因链（推理）**：βwake 在 RRExDff.out.fire 那拍发，假设 N+3 拍 PRF 已写。dual-Load 模式 Memory 反压频率上升 → 生产者卡 ExMemDff 多拍 → Refresh 推迟到 N+4 甚至更晚 → 消费者基于 βwake 的 N+3 RR 读 stale。
- **已尝试**：
  1. 仅 RRExDff.fire 门控 → 仍有 ExMemDff 反压窗口；
  2. RegNext 延迟 1 拍 → 仍不充分；
  3. 完全禁用 βwake → 触发 Bug B。
- **修复方向**：见 PLAN.md 第十节 10.2。

### Bug B — βwake 全禁暴露的 algo_list_reverse 独立 bug

- **触发用例**：`algo_list_reverse_ooo4_unroll`
- **现场**：cycle 0x306b commit lane2 RTL PC=910 vs REF PC=6e4。RTL 在 PC=6e0 `beq a2, zero, 910` 误判 a2==0 取分支，REF 判 a2≠0 fall-through。
- **代码段**：
  ```
  6d4: lw  a0, 4(a1)
  6d8: beq a0, zero, 910
  6dc: lw  a2, 4(a0)        ← a2 应为非零，但 RTL 看到 0
  6e0: beq a2, zero, 910    ← FAIL
  ```
- **关键实证**：
  - `dual-Load + βwake 全禁`：69/70（algo_list_reverse FAIL；kernels_sum_unroll PASS）
  - `doubleLoadStall + βwake 全禁`：仍 algo_list_reverse FAIL → 与 dual-Load 解耦，纯 βwake-off 触发
  - `doubleLoadStall + βwake lane0 (v18)`：70/70 PASS
- **根因未定位**。猜想：βwake 端口 valid 永 0 后 IssueQueue 组合旁路（IssueQueue.scala:155-181）的 dead-input 优化路径改变某 ALU/BCT/ROB 的事件顺序。
- **下次会话调查脚本**：抓 `algo_list_reverse_ooo4_unroll.vcd`（170MB），用 wal trace PC=6dc 周边 a2 wdata + Issue/IQ wakeup 时序，对照 v18 baseline 同 PC 时序差异。

---

## v19.3 验收 —— TD-E 闭环

### Bug A 修复实证
- **修复方案**：βwake 加入第 3 重门控 `!anyLoadInExBundle`（`MyCPU.scala:548`）。
- **理论依据**：当 RR/Ex bundle 内 lane1 是 Load 时，该 Load 在 N+1 拍进 Memory 触发 `memStall`，ExMemDff 整 bundle 被卡住，包括同 bundle 的 ALU 生产者；其在 N+3 拍尚未抵达 Refresh，破坏 βwake N+3 PRF 假设。三重门控直接拦截"同 bundle 含 Load"场景，从源头消除 RAW 竞态。
- **实证**：`kernels_sum_unroll_ooo4_unroll` 在 `doubleLoadStall 删除 + βwake 三重门控` 下 **PASS**（v19.2 同样配置 v3 双重门控仍 FAIL）。
- **回归**：sim-basic 39/39 + sim-regressive **70/70 PASS**。

### Bug B 状态
- **未修**，但被绕开：v19.3 方案保留 βwake-on（lane0），不触发 βwake 全禁场景。
- 下个里程碑深挖；调查路径见 v19.2 章节末尾"下次会话调查脚本"。

---

## v20 验收 —— lane2/3 βwake 启用 ✅

### 改动
- `MyCPU.scala:560-568`：`betaEnable` 由 `(k.U === 0.U)` → `(k.U === 0.U) || (k.U === 2.U) || (k.U === 3.U)`。
- 三重门控（`!anyLoadInExBundle` + `Memory.in.ready` + `RRExDff.fire`）**完整保留**。

### rubber-duck 评审关键结论
**直觉陷阱**：lane2/3 是 ALU-only，永不可能成为 Load → 看似无需 `!anyLoadInExBundle`。
**真相**：bundle 是原子流水。`Memory.memStall` 拉高时整个 ExMemDff 全停，lane2/3 ALU 生产者照样多滞 1 拍 →
N+3 PRF 写时点后移 → βwake 唤醒的消费者按 N+3 读 stale data → **Bug A 复现**。
**结论**：lane2/3 βwake 必须保留三重门控。本次实现严格遵循。

### 实证
- sim-basic **39/39 PASS** + sim-regressive **70/70 PASS**；
- 关键 IPC 收益（vs v18 baseline）：
  - `kernels_load_latency_hide_baseline` +11.8%
  - `kernels_memcpy_like_baseline` +10.7%
  - `kernels_memcpy_like_ooo4_unroll` +9.6%
  - `kernels_dep_chain_ooo4_unroll` 创新高 2.063（v19.3 起跑点 1.396，+47.8%）；
- `algo_array_ops_ooo4_unroll` 仍 −4.0% vs v18，待 P1（lane1 βwake）回收。

### Bug A / Bug B 状态
- **Bug A**：v19.3 已修复（三重门控）；v20 验证扩展到 lane2/3 不破坏修复。
- **Bug B**：未修，被绕开（lane0/2/3 βwake-on，不再触发全禁场景）；待 v21+ P3 深挖。
