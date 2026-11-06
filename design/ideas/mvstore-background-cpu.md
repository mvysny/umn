# MVStore's background writer burns CPU with nothing to write

Seen on aedict-online.eu, 2026-11-06, `umn-proxy-server` 0.17 23 h after its start: the thread
`MVStore background writer` had used 6 362 s of CPU, 7.6 % of the box's one CPU around the
clock, 7.1 % in a 10 s sample, while writing 24 KB in those 10 s (10 MB in 23 h) and reading
nothing. No other thread comes close; the GC (`VM Thread`) had 4.8 s. How it was measured:
`../aedict/aedict-online/design/ideas/jvm-stats-in-production.md`.

Not urgent: it costs CPU, not correctness. But it is a constant 7 % on a 1-CPU box, it would
move to the Hetzner box with the log, and it may grow with the log.

## The suspect: auto-compaction

`MVStoreGlobalLogProvider` opens the store with `MVStore.Builder().compress().fileName(…)`: the
defaults, so `autoCommitDelay` 1000 ms and `autoCompactFillRate` 40. H2 1.4.198's
`MVStore.writeInBackground()` then, once a second:

1. `tryCommit()`: nothing to commit with no clients.
2. `compact(targetFillRate, autoCommitMemory)`, the target 40 % (13 % if the file was read or
   written since the last time). `findOldChunks()` computes the store's fill rate, and if it is
   below the target, walks every chunk, gives each a priority and sorts them all, then rewrites
   at most `autoCommitMemory` bytes' worth.

A log that only grows, with deleted users' items in it, may sit below 40 % full for good: then
the walk and the sort happen every second over every chunk, and the rewrite moves little or
nothing. The CPU would grow with the chunk count, so with the log. Unproven: it is only the most
plausible reading of the code.

## Reproduce it locally, with no clients

The data: `~/backups/aedict-online/2026-11-05T08-31-01/umn-db/umn_proxy_log.zst`, production's
log from one stop (705 MB compressed). It holds every user's synced data: unpack it into a
private directory outside every repo (`chmod 700`), never into the backup's own, and delete it
afterwards.

1. `zstd -d` it into a scratch directory; check the free disk first (the unpacked size is unknown,
   probably 1–2 GB).
2. `MVStoreTool.info(file)` (H2 1.4.198, the same `h2-mvstore` jar): the chunk count and the fill
   rate. Below 40 % backs the suspect.
3. Run `umn-proxy-server` 0.17 on the copy: the image from
   `~/backups/aedict-online/images-2026-11-05.tar.zst` or Docker Hub under podman, or the build
   from this repo; a `config.yaml` with its `db:` at the copy and no `secure:`; no port published
   beyond localhost, no client connected. To mimic the droplet: one CPU (`--cpus 1`,
   `-XX:ActiveProcessorCount=1`), `-Xmx512m`, Serial GC.
4. Measure the background writer: per-thread CPU from `/proc/<pid>/task/*/{comm,stat}` over a
   minute, as on the box. Locally a JDK is at hand, so also `jstack` a few times, or a JFR
   recording (`-XX:StartFlightRecording`): which methods the thread spends its time in.
5. The baseline: the same server on an empty `db`. Near zero expected.

Then, depending on what it shows:

- If it is `findOldChunks()`: `MVStore.Builder().autoCompactFillRate(0)` turns auto-compaction off;
  the file only grows then, which a log that only grows does anyway. Or a higher
  `autoCommitDelay`. Or a one-off `MVStoreTool.compact()` of the file offline, during a planned
  stop, if the fill rate is the problem (it keeps every entry, so the log stays as long; still
  a rewrite of the only copy, so on a copy first, and only with a fresh backup).
- If not: whatever the stacks show.

## Open questions

- `Q_was_012`: did UMN 0.12 burn the same? Nobody measured it before the swap to 0.17. Running
  0.12 on the same copy answers it, if its image is still on Docker Hub.
- `Q_h2_version`: a newer `h2-mvstore` may compact differently, but UMN's file format and the
  apps' own MVStore files (`../aedict`) are tied to 1.4.198; out of scope unless the fix needs it.
