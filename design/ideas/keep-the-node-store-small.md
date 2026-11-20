# Keep a node's store small

A node leaves compaction to MVStore's auto-compaction (`D_node_auto_compaction`). Measured
2026-11-20 (`R_mvstore_store_growth`): steady use stays within ~2× on 1.4.200, 3–5× on 1.4.198.
The rest turns on the app's `autoCommitBufferSize`. At Aedict's 256 KB, the idle writer can't
move the chunks of a store bloated before the upgrade, and a first sync's pull runs about 10×
longer. At H2's default, 16 MB on a phone, a few commits bring the web replicas to a median 1.13×
and a first-synced store to 1.0–2.8×. Aedict opens with H2's default since 2026-11-20 (Aedict's
decisions, on the default buffer).

The procedure is Aedict's `StoreGrowthReplayTest`: `replay`
(time-scaled phone use), `firstSync` (a whole-log pull, real time), `idle` (real stores, opened and
left alone); each runs on UMN 0.18 as is, and on HEAD with `--include-build`. Inputs: a backup's
`umn_proxy_log`, and `~/backups/aedict-online/replicas-2026-11-09/userdata`. `UMN_GROWTH_BUFFER_KB`
sets the buffer, `UMN_FIRST_SYNC_SCALE` a phone's slowness.

## What production holds

The web replicas, written by the 1.4.197 Vaadin 8 app over six years with no compaction call:
1 153 MB on disk for 242 MB live, 4.8×; of the 69 over 1 MB, median 4.1×, p90 7.5×, max 13×.
Phones on 1.4.197/1.4.198 likely look alike. The 14 replicas that stayed at 3–5.6× held chunks
pinned by pages 1.4.197 leaked (`R_mvstore_197_meta_leak`); an app now drops those before opening
(`D_drop_leaked_pages`).

Opened as an app on HEAD opens them, leaks dropped and the log pointer open, the 67 without a v1
document went from 1 018 MB to 246 MB for 194 MB live, after two idle minutes, 6 commits and 30 s
at 16 MB (`idle`, 2026-11-20). Median 1.04×, p90 2.0×, max 2.3×; on UMN 0.18, 431 MB, 1.13×,
3.3×, 5.6×. The 20 leaky ones: median 3.17× → 1.05×. Of the 13 clean ones 0.18 left at 2.1–3.0×,
11 closed at 1.01–1.13×, so the log pointer, left closed, had pinned their chunks
(`R_mvstore_unopened_map_pins`). The 7 still at 2.0–2.3× held dead chunks, 0.4–16 MB, inside the
45 s retention; in the 3 largest, 9–18 MB live and chunks 81–87 % live, the commits had set the
writer rewriting them. 6 more commits past the retention brought each to 1.00–1.11×: all 67 end
within 1.13× of their live data.

## Next

- `Q_drop_on_phone`: how long does `dropLeakedPages()`'s first call take on a phone? On a desktop,
  a cold JVM per call: 0.12 s on clean files of 22 and 67 MB; a copy took 0.36 s for 3.5 MB to
  1.5 MB, 1.03 s for 5 MB to 1 MB (6 100 inner pages), 0.33 s for 40 MB to 4.9 MB, 0.40 s for
  45 MB to 10 MB, 0.61 s for 66 MB to 18 MB; every later call 1–3 ms. At a phone's pace (10×, a
  guess), up to 10 s, once. Aedict opens its store in `Application.onCreate()`, on the main thread.
  Measure on the emulator, and if it's too slow, Aedict opens its store off the main thread.
- `Q_first_sync_peak`: does a first sync's transient peak matter, up to 152× live and 350 MB at a
  phone's pace (10× slower than the desktop: a guess)? Only on a phone short of space. If it
  does: a lower `retentionTime` during the pull (its 45 s guards a power cut, not a kill:
  `R_kill_vs_crash`).
