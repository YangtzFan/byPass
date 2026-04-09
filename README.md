# Ten-Stage Pipeline CPU Core in Chisel

## Requirements

- Java 11 or newer
- Mill (or use the wrapper from your environment)

## Quick Start

```bash
cd chisel-template
mill -i chiselTemplate.compile
mill -i chiselTemplate.runMain chiseltemplate.GenerateVerilog
```

## XMake Workflow

```bash
# 初始化子模块
xmake run init

# 编译 Scala/Chisel
xmake run comp

# 格式化 Scala 源码
xmake run fmt

# 生成 RTL 到 build/rtl（IROM 不再在编译期加载测试程序）
xmake run rtl
```

生成的 RTL 输出到 `build/rtl` 目录。

> **注意**: IROM 指令加载已移至 [difftest](../) 框架中实现，切换测试用例无需重新编译 RTL。

## Files

- `build.sc`: Mill build definition
- `src/main/scala/GenerateVerilog.scala`: RTL generation entry
- `src/main/scala/*`: source code files of Chisel project
