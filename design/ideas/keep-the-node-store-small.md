# Keep a node's store small

The owner, 2026-11-20, after the H2 1.4.200 upgrade: `MvstoreUMN.compact()` has no caller, as
neither Aedict nor Aedict-Reader calls it. Remove it, or make it what it was meant to be, a way to
shrink the database and keep it in check, say run every 30 minutes on Android? Brainstorm.

## Where it stands

- `compact()` rewrites the live pages of every open map once, then moves the chunks to the file's
  start: 127 MB → 10 MB in a second on a node-shaped test store. It only works with
  `versionsToKeep` and the retention time at 0 and the background writer stopped. So it must run
  while no other thread uses the UMN, which rules out a timer beside the sync. H2's own
  `compactFile()` never returns on a live store (`R_mvstore_compact_concurrency`).
- Aedict and Reader open the store with the default `autoCompactFillRate`, 90 on 1.4.200 (40 on
  1.4.198): the background writer rewrites and moves chunks by itself whenever it projects the
  file under 90 % full (`R_mvstore_idle_compaction`). A 100 000-chunk test store was compacted
  within seconds. So on 1.4.200 a node may already keep itself small, with no call at all.
- The proxy server's answers: `D_own_compaction_timer`, `compact()` on a timer beside writers, and
  `D_compact_on_startup`, an offline `MVStoreTool.compact()` copy before the store opens.

## Options

- **A. Remove it.** No caller; `Breaking:` line, the migration is "nothing, auto-compaction does it".
  Only if `Q_auto_enough` says yes.
- **B. On a timer, safe beside writers:** `compact(target, write)` as the proxy's timer does, a
  concurrent path, with `versionsToKeep` left alone; the emptied chunks are freed by the next five
  commits, which a node's writes provide. Every 30 minutes, or when the app goes to background?
  Pointless if auto-compaction already does the same every second.
- **C. On start, offline:** `StartupCompaction`'s copy-and-rename before `MvstoreUMN` opens the
  file. Shrinks to the live size, even what 1.4.198 leaked. Costs a start as long as the copy
  and free space the size of the live data; Android kills apps, so it must be crash-safe as
  the proxy's is (`R_kill_vs_crash`). Maybe only when the file is, say, over 3× its live size?
- **D. Keep it as it is**, documented as start-only.

## Open questions

- `Q_auto_enough`: does 1.4.200's auto-compaction keep a real Aedict store small? Measure a
  synced test user's store, and its size after a week of use, on 1.4.198 against 1.4.200, on the
  emulator. Production's stores: how big do they get, how much is dead?
- `Q_battery`: what does the background writer cost on a phone: the walk after each commit
  (`R_mvstore_idle_compaction`, 1–2 ms per 100 000 chunks on a desktop) and its own rewrites?
- `Q_open_maps`: a rewrite moves only open maps' pages. Utility maps an app opens lazily keep
  their old chunks alive; does that matter for Aedict's maps?
