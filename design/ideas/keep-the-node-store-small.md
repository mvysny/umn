# Keep a node's store small

A node leaves compaction to MVStore's auto-compaction (`D_node_auto_compaction`); the owner removed
`MvstoreUMN.compact()` on 2026-11-20, as nothing called it. Whether that is enough on a real
store is not measured yet. Should it fall short, the fallback is below.

## Open questions

- `Q_auto_enough`: does 1.4.200's auto-compaction keep a real Aedict store small? Measure a
  synced test user's store, and its size after a week of use, on 1.4.198 against 1.4.200, on the
  emulator. Production's stores: how big do they get, how much is dead? The web replicas: 1.1 GB
  across 709 (2026-10-30), about 1.6 MB each, written by the 1.4.197 Vaadin 8 app with no
  compaction call ever; their dead share is unmeasured.
- `Q_battery`: what does the background writer cost on a phone: the walk after each commit
  (`R_mvstore_idle_compaction`, 1–2 ms per 100 000 chunks on a desktop) and its own rewrites?
- `Q_open_maps`: a rewrite moves only open maps' pages **[unverified]**. Utility maps an app opens
  lazily keep their old chunks alive; does that matter for Aedict's maps?

## The fallback: an offline copy on start

`StartupCompaction`'s copy-and-rename before `MvstoreUMN` opens the file (`D_compact_on_startup`).
Shrinks to the live size, even what 1.4.198 leaked (`R_mvstore_removemap_leaks`). Costs a start as
long as the copy and free space the size of the live data; Android kills apps, so it must be
crash-safe as the proxy's is (`R_kill_vs_crash`). Maybe only when the file is, say, over 3× its
live size?
