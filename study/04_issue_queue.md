# 04 · 发射队列（IssueQueue）

> **角色**：48 项乱序保留站；缓存等待 src ready 的指令；接收 12 路 wakeup 广播；oldest-ready 选择 4 lane 同时发射。

## 1. 源文件

- `dataLane/IssueQueue.scala`（核心）
- 配置：`CPUConfig.scala:55-61` `issueQueueEntries = 48`，`iqIdxWidth = log2Ceil(48) = 6`。

## 2. 容量与字段

| 配置 | 值 |
|---|---|
| 项数 | 48 |
| 索引位宽 | 6 bit |
| `NumWakeupSrcs` | `refreshWidth + memoryWidth + executeWidth = 4 + 4 + 4 = 12` |
| 每槽位字段 | `valid`、`busy`、`pdst`、`psrc1/2`、`src1/2Ready`（Reg）、`robIdx`、`sbIdx`、`instSeq`、type bits |

> v21 修订注释：原"≤16 活跃条目"已改为"≤48"；位宽"4 位"已改为"6 位"。

## 3. 数据通路

```
入队（来自 Dispatch 4 lane）
    ↓
[48 项 Vec]  ←─── 12 路 wakeup 同拍命中 → src{1,2}Ready Reg 下一拍翻 1
    ↓
oldest-ready 仲裁（按 instSeq 循环序号比较）
    ↓
4 lane 同时发射（合规 lane mask 后输出）
    ↓
出队（清 valid，槽位回 freeMask）
```

## 4. wakeup 接收（line 153-186）

- **入队时（同拍 src ready 初始化）**：line 153-167
  - `src1ReadyInit = ReadyTable[psrc1] || (∃ k. wakeup[k].valid && wakeup[k].pdst === psrc1)`
- **驻留时（Reg 翻转）**：line 175-186
  - 每拍扫 12 路 wakeup，命中即把对应槽的 `src1/2Ready` Reg 在下一拍翻 1。

> 关键设计：`src1/2Ready` 是 **Reg**，wakeup 在 N 拍命中→N+1 拍 ready=1→N+1 拍 oldest-ready 仲裁可选中→N+1 拍发射。这就是 βwake 在 N+3 拍能跨过的"基线"。

## 5. oldest-ready 选择

- `instSeq`（`instSeqWidth=8`）是 8-bit 循环序号，每个进入 IQ 的指令都打一个；
- 对每个 lane，遍历所有 ready 槽位，按"按 instSeq 循环序号比较"挑最老的；
- 不同 lane 间互斥（一个槽不能同拍被两 lane 选中）。

## 6. v21 已落地清理

- 删除"阶段 α.1（已暂时回滚）"整段过渡说明；
- 折叠 `wk1ext/wk1`、`hitExt1/hit1` 死中转变量（fastWk Reg 路径已被前序里程碑移除）。

## 7. 关键不变量

1. **入队 src ready 必须叠同拍 wakeup**（避免漏唤醒）；
2. **wakeup 是 Reg，N 拍广播 → N+1 拍生效**：βwake N+3 写 PRF 与 N+3 拍消费者读 PRF 之间，依赖 PRF 同拍写优先 bypass；
3. **flush=1 时**：所有槽 valid=0（重定向冲刷整体清空）。

## 8. 调试切入点

- IPC 偏低怀疑 IQ 满：dump IQ `freeCnt` 历史；持续 < 4 → 后端瓶颈传到前端；
- 怀疑漏唤醒：在 wakeup 命中处加断言：若 wakeup pdst 命中某槽 src，下一拍该槽 srcReady 必须=1；
- 怀疑序号回绕错误：dump `instSeq` 模式，确认 oldest-ready 比较器位宽与配置一致。
