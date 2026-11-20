# Keep a node's store small

A node leaves compaction to MVStore's auto-compaction (`D_node_auto_compaction`); the owner removed
`MvstoreUMN.compact()` on 2026-11-20, as nothing called it. Measured 2026-11-20
(`R_mvstore_store_growth`): steady use stays within ~2× on 1.4.200, 3–5× on 1.4.198. Two cases
still bite: a first sync, and stores bloated before the upgrade that 1.4.200 can't move.

The procedure is Aedict's `StoreGrowthReplayTest`: `replay`
(time-scaled phone use), `firstSync` (a whole-log pull, real time), `idle` (real stores, opened and
left alone); each runs on UMN 0.18 as is, and on HEAD with `--include-build`. Inputs: a backup's
`umn_proxy_log`, and `~/backups/aedict-online/replicas-2026-11-09/userdata`.

## What production holds

The web replicas, written by the 1.4.197 Vaadin 8 app over six years with no compaction call:
1 153 MB on disk for 242 MB live, 4.8×; of the 69 over 1 MB, median 4.1×, p90 7.5×, max 13×.
Phones on 1.4.197/1.4.198 likely look alike.

## Open questions

- `Q_first_sync`: how big does a first sync's pull grow a store at a phone's speed? On a desktop,
  40–650× the live data. Options: a lower `retentionTime` during the pull (its 45 s guards a
  power cut, not a kill: `R_kill_vs_crash`), or the offline copy below once the pull completes.
- `Q_stuck`: why do 2 of 10 first-synced stores (the two smallest, under 1 MB live) stay at
  270 MB while idle, and do later edits unstick them? Same for the real replicas 1.4.200 leaves
  at 3–6×. Not the maps an app leaves unopened: stuck and shrinking replicas hold the same ones.
  A lead **[unverified]**: the idle move budget scales with `autoCommitMemory`, and Aedict's
  `autoCommitBufferSize(256)` cuts it from H2's default of up to 19 MB to 256 KB.
- `Q_battery`: what does the background writer cost on a phone: the walk after each commit
  (`R_mvstore_idle_compaction`, 1–2 ms per 100 000 chunks on a desktop) and its own rewrites?

## The fallback: an offline copy on start

`StartupCompaction`'s copy-and-rename before `MvstoreUMN` opens the file (`D_compact_on_startup`).
Shrinks to the live size, even what 1.4.198 leaked (`R_mvstore_removemap_leaks`) and what the
idle writer can't move. Costs a start as long as the copy and free space the size of the live
data, both a few MB on a node; Android kills apps, so it must be crash-safe as the proxy's is
(`R_kill_vs_crash`). Only when the file is, say, over 3× its live size, which
`getChunksFillRate()` estimates without a copy.
