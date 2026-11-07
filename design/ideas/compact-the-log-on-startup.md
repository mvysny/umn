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
- **A rollback still opens it** (`D_frozen_0_15_formats`): the 0.12 image (H2 1.4.197, the
  ultimate rollback) and 0.17 both serve production's compacted log byte for byte as they serve
  the original. Phones never open the server's file, so no 0.15 node is involved.

What it costs: a copy of the live data on every start, ~20 s for 755 MB here, likely a minute or
two on the droplet; and free disk the size of the live data, on the `db` volume, for that while.

## Proposal

On every start (the owner: a constant cost beats a heuristic; restarts are rare deploys), in
`MVStoreGlobalLogProvider`, before opening the store:

1. Delete a leftover `umn_proxy_log.compacting` from an interrupted start.
2. Skip, with a warning, when the `db` volume's `usableSpace` is short of the chunks' live sum, an
   upper bound since it counts leaked pages too: a full disk would starve the running server.
3. `MVStoreTool.compact(log, "$log.compacting", true)`.
4. Check the copy: the same map names, and each map's `sizeAsLong()` equal; cheap, the counts sit
   in the root pages. Counts only: the passthrough is verified once, and UMN's own tests do the
   full key-by-key comparison.
5. `FileChannel.force(true)` on the copy, `Files.move(…, ATOMIC_MOVE, REPLACE_EXISTING)` over the
   log, `force` on the directory.
6. Any failure: delete the copy, log a warning with the cause, open the original as today.
7. Log the sizes before and after, and the time it took.

`compactDatabaseOnStartup: false` in `config.yaml`, under `proxy.standalone`, turns it off; it
defaults to on. Tests embedding `Main` with an empty `db` pay nothing.

Tests:

- `umn-proxy-server`: a store with garbage, restarted, comes back smaller with every key and value
  equal, key by key; a leftover `.compacting` is deleted; too little disk, or a failing copy,
  starts on the original; the switch off leaves the file alone.
- `umn-compat-0.15`, the rollback: the 0.12 image writes a log (accounts, a deleted user), HEAD's
  server takes it over and compacts it on startup, then a 0.12 server opens the result and its
  0.15 nodes sync on, as in `ServerUpgradeTest`. `ProdServer` needs a constructor that starts
  on a given `umn_proxy_log`.

## Roads not taken

- **An operator command run during a planned stop** (`umn-proxy-server compact`): no startup
  cost, but it's a manual step nobody remembers; the leak and the dead space just grow.
- **Only when it pays:** the fill rate can't tell, since leaked pages count as live; a file size
  against what the last compaction left would, but a heuristic is harder to reason about.
- **An admin REST endpoint:** the copy needs the file closed, so it would stop serving anyway.
- **`MVStore.compactMoveChunks()` online:** it packs chunks to the file's start, but still counts
  the leaked pages as live, so the 0.7 GB stays.

## On graduation

- `umn-proxy-server/README.md` ("Configuration and Deployment") and
  `umn-proxy-server/src/main/docker/README.md`: the startup now takes as long as copying the
  live data; it needs free disk the size of the live data on the `db` volume; a kill during it
  is safe; and how to turn it off with `compactDatabaseOnStartup: false`.
- `CHANGELOG.md`: a line for the startup compaction and the switch.
- `design/decisions.md`: a `D_` for compacting offline on every start, its roads not taken.
