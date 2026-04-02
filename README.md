# Eight-Stage Pipeline CPU Core in Chisel with cdp-test verification framework from HITSZ

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
# Initialize submodules
xmake run init

# Compile Scala/Chisel
xmake run comp

# Format Scala sources
xmake run fmt

# Generate RTL to build/rtl
xmake run rtl

# Build simulator (Verilator output in build/obj_dir)
xmake run sim-build

# Run one test (default uses IROM_BIN=and)
xmake run sim-run

# Run a specific test program
IROM_BIN=add xmake run sim-run

# Run all bin programs and print summary
xmake run sim-all
```

Generated RTL will be emitted to `build/rtl`.

Waveform output is written to `build/waveform`.

Batch simulation logs and summary are written to `build/sim-all`:
- `build/sim-all/<case>.log`: full log for each case
- `build/sim-all/summary.txt`: pass/fail list

`sim-all` uses the following pass criteria per case:
- Process exit code is `0`
- Log contains `Test Point Pass!`

## Files

- `build.sc`: Mill build definition
- `src/main/scala/GenerateVerilog.scala`: RTL generation entry
- `src/main/scala/*`: source code files of Chisel project
