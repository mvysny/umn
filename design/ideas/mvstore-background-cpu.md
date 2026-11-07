# MVStore's background writer burns CPU with nothing to write

Seen on aedict-online.eu, 2026-11-06, `umn-proxy-server` 0.17 23 h after its start: the thread
`MVStore background writer` had used 6 362 s of CPU, 7.6 % of the box's one CPU around the
clock, 7.1 % in a 10 s sample, while writing 24 KB in those 10 s (10 MB in 23 h) and reading
nothing. No other thread comes close; the GC (`VM Thread`) had 4.8 s. How it was measured:
`../aedict/aedict-online/design/ideas/jvm-stats-in-production.md`.

Not urgent: it costs CPU, not correctness. But it is a constant 7 % on a 1-CPU box, it would
move to the Hetzner box with the log, and it may grow with the log.

## The cause: an idle store recomputes its fill rate ten times a second

Reproduced 2026-11-07 on the 2026-11-06 backup (3.95 GB unpacked), opened as
`MVStoreGlobalLogProvider` opens it (`MVStore.Builder().compress().fileName(…)`, so
`autoCommitDelay` 1000 ms, `autoCompactFillRate` 40), idle, one CPU, `-Xmx512m`, Serial GC:

| run, 60 s idle | writer CPU |
|---|---|
| production copy, defaults | 5.5 % (4.7 % over 180 s) |
| production copy, `autoCompactFillRate(0)` | 0.1 % |
| empty store, defaults | 0.1 % |

No bytes written or read during the idle runs. JFR: 99 % of the writer's samples are in
`MVStore.getCurrentFillRate()`, called from `findOldChunks()`; the sort and the rewrite don't show.

H2 1.4.198's mechanism: the background thread wakes every `autoCommitDelay / 10` = 100 ms and
calls `writeInBackground()`, which skips only while `time <= lastCommitTime + autoCommitDelay`.
`lastCommitTime` moves only in `storeNow()`, i.e. when something is actually stored, so in an idle
store it stays put and every wake-up goes on to `compact()` → `findOldChunks()` →
`getCurrentFillRate()`, which walks every chunk: 129 299 of them, 2–6 ms a walk here, ten walks a
second. At 42 % fill, above the 40 % target, `findOldChunks()` returns right after that walk.

The file: 129 299 chunks, median 5 blocks (20 KB), one per small sync commit; 3.4 GB of chunks,
1.47 GB live. The cost is linear in the chunk count, so it grows with the log; production's
writes (24 KB/10 s) are the real traffic resetting `lastCommitTime`, not compaction.

## The fix

- `autoCompactFillRate(0)` alone: the CPU is gone, but partly dead chunks are never rewritten
  again; wholly dead ones are still freed and their space reused, so the file grows faster than
  the live data.
- `autoCompactFillRate(0)` plus our own `MVStore.compact(40, …)` on a timer (say once a
  minute): today's file-size control at ~1/600 of the walks. The likely pick.
- A higher `autoCommitDelay`: rejected, it widens the window of acknowledged records a crash
  loses.

## Open questions

- `Q_was_012`: did UMN 0.12 burn the same? Nobody measured it before the swap to 0.17. Running
  0.12 on the same copy answers it, if its image is still on Docker Hub.
- `Q_h2_version`: a newer `h2-mvstore` may compact differently, but UMN's file format and the
  apps' own MVStore files (`../aedict`) are tied to 1.4.198; out of scope unless the fix needs it.
