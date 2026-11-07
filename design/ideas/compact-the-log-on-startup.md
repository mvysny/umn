# Compact the proxy log offline, on every startup

The owner, 2026-11-07, after `ce7e57d` (our own compaction timer) and `2553a92` (deleted users'
logs freed).

## The problem

Online compaction keeps the log at least 40 % full (`D_own_compaction_timer`), so the file may be
2.5× its live data, and it can't recover what deleted users leaked before `2553a92`: those pages
still count as live (`R_mvstore_removemap_leaks`). Production's log is 3.77 GB; an offline
`MVStoreTool.compact()` makes it 755 MB, byte-exact (`R_mvstoretool_compact`).

## Is it safe?

Yes, done right. From `R_mvstoretool_compact`:

- **The original is never touched.** The three-argument `compact(source, target, compress)`
  opens it read-only and writes a new file; a crash or a full disk mid-copy leaves the original
  as it was, plus a temp file to delete.
- **The copy is byte-exact**, verified on production's log: every key and value of 2.26 M
  entries.
- **What H2 doesn't do, we must:** sync the copy before the swap (`FileStore.close()` never calls
  `force()`), swap with an atomic rename, then sync the directory.

What it costs: a copy of the live data on every start, ~20 s for 755 MB here, likely a minute or
two on the droplet; and free disk the size of the live data, on the `db` volume, for that while.

## Proposal

In `MVStoreGlobalLogProvider`, before opening the store:

1. Delete a leftover `umn_proxy_log.compacting` from an interrupted start.
2. `MVStoreTool.compact(log, "$log.compacting", true)`.
3. Check the copy: the same map names, and each map's `sizeAsLong()` equal; cheap, the counts sit
   in the root pages. `Q_verify`.
4. `FileChannel.force(true)` on the copy, `Files.move(…, ATOMIC_MOVE, REPLACE_EXISTING)` over the
   log, `force` on the directory.
5. Any failure: delete the copy, log a warning with the cause, open the original as today.
6. Log the sizes before and after, and the time it took.

A `config.yaml` switch turns it off: `compactOnStart`, `Q_config`.

## Roads not taken

- **An operator command run during a planned stop** (`umn-proxy-server compact`): no startup
  cost, but it's a manual step nobody remembers; the leak and the dead space just grow.
- **An admin REST endpoint:** the copy needs the file closed, so it would stop serving anyway.
- **`MVStore.compactMoveChunks()` online:** it packs chunks to the file's start, but still counts
  the leaked pages as live, so the 0.7 GB stays.

## Open questions

- `Q_every_start`: always, or only when it pays? The fill rate can't tell: leaked pages count as
  live. File size against the size the last compaction left (kept beside the log) could: skip
  while the file is under, say, 1.5× that. I lean towards always: restarts are deploys, rare,
  and a constant cost is easier to reason about than a heuristic.
- `Q_config`: `compactOnStart` under `proxy.standalone`, default on? Tests embedding `Main` with
  an empty `db` pay nothing either way.
- `Q_disk`: check `usableSpace` first and skip with a warning when it's short of the chunks'
  live sum (an upper bound: it counts leaked pages too)? Or just let the copy fail on a full disk
  and fall back (step 5)? A full disk also starves the running server, so skipping is kinder.
- `Q_verify`: counts only (step 3), or the full key-by-key comparison, 12 s on production's log?
  The passthrough is verified once; counts catch a truncated copy.
- `Q_compat_015`: `D_frozen_0_15_formats` — the compacted file must open in a 0.15 and a 0.17
  server, for a rollback. It's H2 1.4.198's own format with new map ids; `umn-compat-0.15`'s
  `ServerUpgradeTest` pattern can prove it against the production image.

## On graduation

- `umn-proxy-server/README.md` ("Configuration and Deployment") and
  `umn-proxy-server/src/main/docker/README.md`: the startup now takes as long as copying the live data; it needs
  free disk the size of the live data on the `db` volume; a kill during it is safe; and how to
  turn it off.
- `CHANGELOG.md`: a line for the startup compaction and the switch.
- `design/decisions.md`: a `D_` for compacting offline on every start, its roads not taken.
