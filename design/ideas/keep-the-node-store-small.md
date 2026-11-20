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
Phones on 1.4.197/1.4.198 likely look alike.

## What 1.4.197 left behind

The 14 replicas that stay at 3–5.6× hold chunks that 1.4.197 left counting one meta page live
after replacing it (`R_mvstore_197_meta_leak`). 1.4.200 can neither free nor rewrite those chunks,
so they stay at full length for good. Worse, its idle writer never rests while the store is open:
10 fsyncs a second, and tens to hundreds of MB written a minute. Aedict ran 1.4.197 for years, so
phone stores likely carry such chunks too, and 0.19 brings 1.4.200 to aedict-web's replicas and
then to phones. Three cures, the last two each a copy that leaves those pages behind:

- **Accept it.** The space is bounded: 2.1–4.5× live on the replicas. The churn, battery and
  flash wear on a phone, is not.
- **The offline copy on start** (below), triggered exactly: a chunk counting more live bytes than
  its maps' trees reach in it. The meta's `chunk.` entries give the counts through public API.
  The sibling-copy-and-rename it needs is the 2.x migrator's too (`upgrade-h2-to-2.md`).
- **The 2.x migration**: it copies anyway, so it drops them as well. But it's no reason for 2.x
  on its own, since 1.4's copy does the same for less.

## Next

- `Q_clean_2x`: 9 replicas with no phantom page still sat at 2.1–3.0× 30 s after the commits;
  the 2 clean ones traced reached 1.01–1.03×. Untraced: more commits, or something else?
- `Q_first_sync_peak`: does a first sync's transient peak matter, up to 152× live and 350 MB at a
  phone's pace (10× slower than the desktop: a guess)? Only on a phone short of space. If it
  does: a lower `retentionTime` during the pull (its 45 s guards a power cut, not a kill:
  `R_kill_vs_crash`).

## On graduation

- The README tells a node to open its store with H2's default `autoCommitBufferSize`: a small one
  leaves an old store 2–5× its live data for good, silently, and slows a first sync about 10×.
- The README and CHANGELOG warn an app upgrading from UMN 0.15 or older (H2 1.4.197): its stores
  may keep chunks 1.4.200 never frees, and its idle writer then rewrites the live data over and
  over for as long as the store is open, silently. Unless the cure above ships.

## The fallback: an offline copy on start

`StartupCompaction`'s copy-and-rename before `MvstoreUMN` opens the file (`D_compact_on_startup`).
Shrinks to the live size, even what 1.4.198 leaked (`R_mvstore_removemap_leaks`) and what the
idle writer can't move. Costs a start as long as the copy and free space the size of the live
data, both a few MB on a node; Android kills apps, so it must be crash-safe as the proxy's is
(`R_kill_vs_crash`). Only when the file is, say, over 3× its live size, which the file's and the
chunks' fill rates estimate without a copy (`getFillRate()` × `getChunksFillRate()`); neither
sees what 1.4.198 leaked, and they see 1.4.197's phantom chunks only once the writer has emptied
them, an open later.
