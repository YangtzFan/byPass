# 07 · Execute（双 ALU + EX-in-EX 旁路 + 分支验证）

> **角色**：4 lane bundle 内执行 ALU 运算；lane0 / lane1 计算分支条件并发 redirect 信号；EX-in-EX 旁路解决 lane0→lane1 同 bundle RAW。

## 1. 源文件

- `dataLane/Execute.scala`（核心，~150 行）
- 配置：`executeWidth = 4`

## 2. ALU 实例

| lane | ALU | 类型支持 |
|---|---|---|
| lane0 | 独立 ALU 实例 | iType / rType / uType / Branch / JALR / Load 计算地址 / Store 计算地址 |
| lane1 | 独立 ALU 实例 | 同上（Full lane）|
| lane2 | ALU 实例 | iType / rType / uType（ALU-only）|
| lane3 | ALU 实例 | 同 lane2 |

## 3. EX-in-EX 同拍前递（line 71-96）

> 解决 lane0 → lane1 同 bundle RAW：lane0 ALU 结果同拍前递给 lane1 src1/src2（不经过 PRF）。

```scala
val lane0AluOut = ...  // lane0 ALU 输出（组合逻辑，本拍可见）
when (lane1.psrc1 === lane0.pdst && lane0.writesPdst) {
  lane1.src1Eff := lane0AluOut
}
// src2 同理
```

- **限制**：仅 lane0 → lane1，反向不支持（按 `pairRaw` 规则被 Issue 阶段排除）；
- **不覆盖 Load**：Load 在 Memory 阶段才出结果，EX-in-EX 不能前递 Load 数据 —— 这是为什么 `pairRaw` 必须 stall lane k 读 Load lane j.pdst 的情形。

## 4. 分支验证（lane0 / lane1）

- 计算实际 target；
- 与 BTB / BHT 预测的 target / direction 比较；
- 不一致 → 触发 `lane0MispredictEarly` / `lane1MispredictEarly`；
- Memory 阶段 redirect 真正生效（通过 ExMemDff 传递）。

> 单 bundle 内最多 1 条 Branch 被仲裁通过（`doubleBrStall` 见 §05）。

## 5. BHT / BTB 更新

- 更新写口位于 Execute 阶段；
- Memory.redirect=1 时**抑制更新**（防错误路径污染）。

## 6. 关键不变量

1. **lane0→lane1 EX-in-EX 旁路不可缺**：缺则 lane0 ALU 生产者→lane1 ALU 消费者的同 bundle RAW 取错值；
2. **Load 不走 EX-in-EX**（Load 没有 ALU 输出 data，仅有 addr）；
3. **mispredict 同步**：分支验证结果必须随 ExMemDff 一起流到 Memory 阶段，由 Memory.redirect 端口统一发出。

## 7. 调试切入点

- ALU 算错：dump src1Eff/src2Eff（是否来自 EX-in-EX）+ ALU op；
- mispredict 漏报：检查 `lane0MispredictEarly` 触发条件 + Memory.redirect 是否屏蔽；
- BHT 学习错：怀疑 redirect 抑制是否生效。
