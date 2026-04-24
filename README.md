# byPass — A 10-stage RISC-V CPU (Real OoO 1-issue, Lane-ified Payloads)

> This branch finishes phases 1 and 2 of `TASK.md` on top of the original
> 10-stage in-order pipeline: **real out-of-order 1-issue (OoO-1issue)** plus
> **full `Vec(width, Lane) + validMask` lane-ification of the backend payloads**.
> The backend is still 1-wide, but every data structure and config knob is now
> parameterized by `issueWidth / executeWidth / memoryWidth / refreshWidth`,
> leaving clean hooks for the phase 3~6 extensions to 2-issue and 4-issue.

## Prerequisites

- Java 11+
- Mill (wrapper shipped in the repo)
- [xmake](https://xmake.io/) (for RTL generation and running difftest)

## Quick start

```bash
# Initialize submodules
xmake run init

# Compile Scala / Chisel
xmake run comp

# Emit RTL into build/rtl
# (IROM is loaded by difftest at runtime; switching test cases does not
#  require regenerating the RTL.)
xmake run rtl
```

Run the full difftest regression:

```bash
cd ../difftest
xmake b rtl
xmake b Core
xmake r sim-all
```

Run a single test case with waveform dumping (`<name>` is a case under `asm/`):

```bash
cd ../difftest
TC=<name> DUMP=1 xmake r Core
# Waveform lands in difftest/build/verilator/Core/<name>.vcd
```

## Current micro-architecture snapshot

10-stage pipeline:

```
Fetch → FetchBuffer → Decode → Rename → RenDisDff → Dispatch
      → IssueQueue(4-in / 1-out, oldest-ready)
      → Issue → ReadReg → Execute → Memory → Refresh → Commit
```

Key parameters (see `src/main/scala/CPUConfig.scala`):

| Parameter | Value | Description |
|---|---|---|
| `issueWidth` / `executeWidth` / `memoryWidth` / `refreshWidth` | 1 | Backend lane widths, kept at 1 through phase 2 |
| `commitWidth` | 1 | In-order commit width |
| `renameWidth` | 4 | 4-wide frontend rename |
| `robEntries` | 128 | ROB depth |
| `issueQueueEntries` | 16 | IssueQueue depth |
| `prfEntries` | 128 | Physical register file size |
| `sbEntries` | 32 | StoreBuffer depth |
| `axiSqEntries` | 16 | AXIStoreQueue depth |
| `maxBranchCheckpoints` | 8 | Branch checkpoint table slots |
| `instSeqWidth` / `storeSeqWidth` | 8 | Circular sequence number widths |

## Phase 1 changes against the original in-order baseline

1. **ReadyTable**: bulk-read (8 ports) at Rename, set at Refresh. On a branch
   flush, the table is restored as `snapshot | refreshedSinceSnap | thisCycleRefresh`,
   so refreshes that happened concurrently with the flush are not lost.
2. **IssueQueue**:
   - Every entry gains `src1Ready / src2Ready`, initialized from ReadyTable read ports at enqueue.
   - A new `wakeup : Vec(refreshWidth, IQWakeupSource)` port lets each refresh
     broadcast its `pdst` to both source fields of every IQ entry.
   - The issue selector moved from "oldest-valid" to **oldest-ready**
     (`valid && src1Ready && src2Ready`).
   - **Selective flush by `robIdx`**: only strictly younger entries are cleared;
     the branch itself is preserved.
   - **Memory ordering constraint**: a load is only issuable when no older
     store still lives in IQ. Without this, a young load that enters Memory
     would stall on `olderUnknown`, blocking the older store from the single
     Memory port and deadlocking the pipeline.
3. **BranchCheckpointTable**: each slot gains `refreshedSinceSnap (128 bit)`,
   which tracks pdsts refreshed after the snapshot. On recovery we OR it back
   into the snapshot, preventing entries that got refreshed pre-flush from
   being mistakenly rolled back to unready.
4. **BaseDff (selective-flush rewrite)**: ExMem / RRRR / IssRR pipeline
   registers compare `robIdx` against `memRedirectRobIdx` and only clear
   strictly-younger entries. The handshake is rewritten as
   `canReceive = !validReg || out.ready || killCur`, with `acceptNew / keepCur`
   decoupled, fully eliminating the "flush same-cycle silently drops data" bug.
5. **Issue-stage flush**: older entries are still allowed to issue on the same
   cycle as a branch flush; a new `flushBranchRobIdx` port keeps IQ and
   BaseDff in sync on age comparison.
6. **CPUConfig width knobs**: new parameters
   `issueWidth / executeWidth / memoryWidth / refreshWidth / prfReadPorts / prfWritePorts`,
   all left at 1 in phase 1 to provide the single entry point for phases 2~6.

## Phase 2 — backend payload lane-ification

To pave the way for 2-issue in phase 3, phase 2 rewraps the four backend
pipeline payloads into `Vec(width, Lane) + validMask: UInt(width.W)`:

| Payload | Lane type | Wrapper width |
|---|---|---|
| `Issue_ReadReg_Payload`   | `Issue_ReadReg_Lane`  | `issueWidth` |
| `ReadReg_Execute_Payload` | `ReadReg_Execute_Lane` extends `Issue_ReadReg_Lane` (adds `src1Data / src2Data`) | `issueWidth` |
| `Execute_Memory_Payload`  | `Execute_Memory_Lane` | `memoryWidth` |
| `Memory_Refresh_Payload`  | `Memory_Refresh_Lane` | `refreshWidth` |

Accompanying conventions:

- **`validMask(i)` is the authority on "should lane i be processed by the
  consumer?"** `Decoupled.valid` only means "the packet is moving". For
  width 1, the two must agree (`out.valid := canIssue` and
  `out.bits.validMask := canIssue.asUInt`).
- Producers first set `out.bits := DontCare`, then write each lane and
  `validMask` explicitly, so uninitialized lanes are never treated as valid
  after widening.
- Consumer stages (ReadReg / Execute / Memory / Refresh) use
  `val inL = in.bits.lanes(0); val outL = out.bits.lanes(0)` as access aliases;
  intra-stage logic is otherwise identical to phase 1.
- `BaseDff.getRobIdx` is upgraded to `b => b.lanes(0).robIdx`; selective flush
  still works entry-by-entry on age.
- The `IQIssue` interface remains scalar (`DispatchEntry`) for now; it will be
  Vec-ified when IQ becomes 2-issue in phase 3.

This phase is behaviorally a no-op — `sim-all` keeps passing 41/41.

## Directory layout

- `src/main/scala/CPUConfig.scala` — global parameter block.
- `src/main/scala/MyCPU.scala` — top-level wiring, bypass network, selective-flush plumbing.
- `src/main/scala/dataLane/` — pipeline stages
  (Fetch/Decode/Rename/Dispatch/IssueQueue/Issue/ReadReg/Execute/Memory/Refresh/Commit).
- `src/main/scala/device/` — ROB, PRF, RAT, FreeList, StoreBuffer,
  AXIStoreQueue, BranchCheckpointTable, ReadyTable, BHT, IROM, DRAM, ...
- `scripts/STUDY.md` — OoO micro-architecture notes for this refactor (read this first).
- `scripts/wave_debug/SKILL.md` — waveform debugging workflow.
- `TASK.md` — full phase 1~6 task spec.

## Next phases

The phase 2~6 change lists live in `TASK.md`. The six width knobs in
`CPUConfig` are the single entry point — no more scattered `_0 / _1` patches.
