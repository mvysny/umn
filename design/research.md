# Research — H2 MVStore

What the things we don't own actually do. About *them*, never us: a sentence starting "we chose"
is a `D_`. `## R_<slug> — <title>`, one claim per bullet, one provenance marker per claim —
**[docs]**, **[src]**, **[verified <date>, <version>]**, **[unverified]** (a hypothesis; a design
built on it says so). A claim is earned by its provenance, or by having cost real work to find
out. Checked against h2-mvstore 1.4.198, the version `D_frozen_0_15_formats` holds us to. Cite by
slug, `R_<slug>`, never by position; `grep '^## R_' design/research.md` is the index. The first
entry is the ruler: every later one trims to its length — which is how long this file gets, so
keep it short. When you have written an entry, re-read it against the one above and cut the fat.

---

## R_mvstore_idle_compaction — an idle store walks every chunk ten times a second

- The background writer wakes every `autoCommitDelay / 10` ms (100 ms by default) and calls
  `writeInBackground()`, which skips the round only while `time <= lastCommitTime +
  autoCommitDelay`. **[src]**
- `lastCommitTime` moves only in `storeNow()`, when something is actually stored. In an idle store
  it stays put, so every wake-up goes on to `compact()` whenever `autoCompactFillRate > 0` (the
  default, 40). **[src]**
- `compact()` → `findOldChunks()` → `getCurrentFillRate()` walks every chunk, even when the fill
  rate turns out above the target and nothing more happens. The cost is linear in the chunk count,
  and a store committed in small writes grows one chunk per commit. **[src]**
- Production's proxy log, 3.95 GB, 129 299 chunks (median 20 KB), 42 % full, idle on one CPU:
  the writer thread used 5.5 % CPU, 2–6 ms a walk; JFR put 99 % of its samples in
  `getCurrentFillRate()`. With `autoCompactFillRate(0)`, 0.1 %, as for an empty store. On the
  droplet's slower vCPU, 7.6 %. **[verified 2026-11-07, 1.4.198]**

## R_mvstore_removemap_leaks — `removeMap()` leaves the map's pages counted live

- `MVStore.removeMap()` drops the map's metadata and closes it, but never marks its pages removed;
  `MVMap.clear()` does, through `removeAllRecursive()`. **[src]**
- So a removed map's pages stay live in the chunks' accounting, and compaction skips the closed
  map: its space is never reclaimed. Three interleaved maps, two removed: 47 % full before and
  after; `clear()` first, 15 %, and compaction then brings it to 49 %.
  **[verified 2026-11-07, 1.4.198]**
- `clear()` is cheap: it swaps in an empty root, then reads only the inner pages, freeing an
  unloaded leaf by its position alone. **[src]**
- Space already leaked comes back only offline: `MVStoreTool.compact()` writes a new file holding
  the maps `getMapNames()` lists, so a removed map's pages are left behind. **[src]**

## R_mvstore_compact_concurrency — `compact()` is safe beside concurrent writers

- With the defaults, the background writer itself calls `compact()` while other threads write;
  it is a supported, concurrent path, not a maintenance-only one. **[src]**
- The rewrite is ordinary map writes: `MVMap.rewritePage()` is `replace(key, value, value)`, on
  the same compare-and-swap root update as `put`. A concurrent change of the value makes the
  replace fail harmlessly. **[src]**
- `compact()` holds `storeLock`, the lock every commit takes, and only via `tryLock(10 ms)`: when
  the store is busy it skips the round rather than wait. **[src]**
- A map removed mid-rewrite is skipped: `rewritePage()` checks `isClosed()`. **[src]**
- On a closed store `compact()` throws `IllegalStateException` (`checkOpen()`). **[src]**
- The file is `nio:` by default, a `RandomAccessFile`'s `FileChannel`: interrupting a thread inside
  its I/O closes the channel for every thread (`ClosedByInterruptException`), and the store is
  dead until reopened. **[src]**, **[docs]** (`InterruptibleChannel`)
