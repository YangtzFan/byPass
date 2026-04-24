# byPass — A 10-stage RISC-V CPU (Real OoO 2-issue, Lane-ified Backend)

> This branch finishes phases 1~5 of `TASK.md` on top of the original
> 10-stage in-order pipeline: **real out-of-order 2-issue (OoO-2issue)** with
> full `Vec(width, Lane) + validMask` lane-ification of the backend payloads.
> Lane 0 is fully featured (branch / memory / ALU); lane 1 is ALU-only.
> All six width knobs (`issueWidth / executeWidth / memoryWidth / refreshWidth /
> commitWidth / renameWidth`) are a single entry point — flipping them to 4
> completes the roadmap to OoO-4issue.

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
      → IssueQueue(4-in / 2-out, dual oldest-ready, lane 1 ALU-only)
      → Issue → ReadReg → Execute → Memory → Refresh → Commit(2-wide)
```

Key parameters (see `src/main/scala/CPUConfig.scala`):

| Parameter | Value | Description |
|---|---|---|
| `issueWidth` / `executeWidth` / `memoryWidth` / `refreshWidth` | 2 | Backend lane widths (lane 0 full, lane 1 ALU-only) |
| `commitWidth` | 2 | In-order 2-wide retire |
| `renameWidth` | 4 | 4-wide frontend rename |
| `prfReadPorts` / `prfWritePorts` | 4 / 2 | PRF ports widened for dual issue |
| `robEntries` | 128 | ROB depth |
| `issueQueueEntries` | 16 | IssueQueue depth |
| `prfEntries` | 128 | Physical register file size |
| `sbEntries` | 32 | StoreBuffer depth |
| `axiSqEntries` | 16 | AXIStoreQueue depth (AXI still 1-in-flight) |
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

## Phase 3~5 — OoO-2issue backend

Phase 3 widens IQ / Issue / ReadReg / Execute; phase 4 widens PRF ports and the
bypass network; phase 5 widens Refresh / ROB / Commit / BCT / FreeList / RAT.

Lane routing rules:

- **Lane 0** handles every op (ALU / Branch / Load / Store).
- **Lane 1** is ALU-only. Loads, Stores, Branches, and JALRs must go to lane 0.
- IQ issues a pair only when (a) lane 1's candidate is ALU-only and (b) there
  is no same-cycle RAW dependency between the two lanes (`rs1/rs2 == pdst0` is
  forbidden; no cross-lane forwarding is built in this round).
- `ROB.commitMask(i) := commitMask(i-1) && entry(i).ready && !storeConflict(i)`.
  Stores are only committed on lane 0; `storeConflict(i)` bars lane 1 from
  committing a second store in the same cycle.
- Selective flush, store-buffer ordering, and AXI single-in-flight guarantees
  are unchanged — phases 3~5 only widen lanes, not the memory / AXI subsystem.

Key multi-lane plumbing:

- `BCT.freeCount : UInt(2.W)` — when two branches commit in the same cycle the
  BCT head advances by 2 (`head := head + freeCount`). A single-bit free valid
  would leak checkpoint slots and eventually deadlock rename (`canSave2 = 0`).
- `FreeList` retires up to `commitWidth` stale pdsts per cycle through a prefix
  counter that writes back into the free-pdst FIFO.
- `ReadyTable` gets `busyVen = 4` (Rename) and `readyVen = refreshWidth`
  (Refresh), with recovery snapshot OR-ed with same-cycle refreshes.
- `PRF` is parameterized by `wPorts / rPorts` (2 W, 4 R at phase 5).
- `MyCPU` forwarding now covers 6 hazard sources per lane (2× memory-stage
  piggyback + 2× refresh + 2× same-cycle commit).

## Validation

41/41 difftest cases pass (`sim-all`). `example` uncovered a latent BCT bug
whose fix is required for dual commit: see the "BCT multi-lane free" note above.

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

Phase 6 (dual-lane memory front-end with parallel SB / SQ forwarding ports)
remains — current `Memory.scala` passes lane 1 through and only lane 0 can
reach the store buffer / store queue. Flipping the six width knobs to 4 and
duplicating lane 1 (plus teaching Memory to dual-issue loads) completes the
roadmap to OoO-4issue.

See `TASK.md` for the full phase 1~6 spec and `scripts/STUDY.md` for the
micro-architecture notes accumulated through this refactor.
