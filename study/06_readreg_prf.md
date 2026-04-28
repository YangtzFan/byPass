# 06 · ReadReg + PRF（同拍写优先 bypass）

> **角色**：从 PRF 读出每条发射指令的 rs1 / rs2 数据；处理同拍 Refresh 写优先（βwake 安全的关键基础设施）。

## 1. 源文件

- `dataLane/ReadReg.scala`
- `device/PRF.scala`

## 2. 端口配置

| 参数 | 值 | 说明 |
|---|---|---|
| `prfEntries` | 128 | p0~p127；p0 硬连 0 |
| `prfAddrWidth` | 7 | log2(128) |
| `prfReadPorts` | 8 | = 2 × `issueWidth` |
| `prfWritePorts` | 4 | = `refreshWidth` |

## 3. 数据通路

```
IssRRDff.out (4 lane, 已仲裁)
    ↓
PRF 读：每 lane 2 读口（rs1/rs2）→ 总共 8 读口
    ↓
同拍写优先 bypass：检测 wen[i] && waddr[i] === raddr → 返回 wdata
    ↓
RRExDff.in（rs1/rs2 数据 + meta）
```

## 4. 同拍写优先 bypass —— `device/PRF.scala`

```scala
val rdata = WireDefault(mem(raddr))
for (i <- 0 until prfWritePorts) {
  when (wen(i) && waddr(i) === raddr) {
    rdata := wdata(i)
  }
}
```

> **意义**：同拍 Refresh 写口写入的数据优先回给读端 → 同拍消费者拿到最新值。这是 **βwake N+3 时序**得以工作的基础（βwake 广播在 N 拍，生产者写 PRF 在 N+3 拍，消费者读 PRF 也在 N+3 拍，靠这个 bypass 拿值）。

## 5. p0 硬连 0

- 任何 raddr=0 的读返回 0（不查 mem）；
- 任何 waddr=0 的写忽略（mem(0) 永远是 0）；
- Rename 阶段已经把 rd=x0 的指令不分配 pdst（直接复用 p0）。

## 6. 关键不变量

1. **写优先 bypass 必须存在**：βwake 全链路依赖此特性；
2. **8 读 / 4 写口**：满足 4-wide 发射 + 4-wide refresh 同拍；
3. **p0 硬连 0**：硬件直接 mux，不实际写入。

## 7. 调试切入点

- 怀疑数据从 PRF 读出错：dump 该 raddr 对应的 mem 项 + 同拍 wen/waddr/wdata；
- 若 βwake 触发数据错误，第一步先验证写优先 bypass 是否被某次重构破坏（grep `wen.*waddr.*===.*raddr`）。
