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
- Space already leaked comes back only offline, from `MVStoreTool.compact()`
  (`R_mvstoretool_compact`): on production's proxy log, 1.47 GB was counted live but 755 MB
  reachable, so ~0.7 GB was the deleted users' logs. **[verified 2026-11-07, 1.4.198]**

## R_mvstoretool_compact — `MVStoreTool.compact()`: a byte-exact offline copy

- `compact(source, target, compress)` opens the source read-only and writes a new target file,
  holding only the maps `getMapNames()` lists: a removed map's pages are left behind. **[src]**
- The copy is a passthrough: `MVMap.copyFrom()` copies the trees page by page, their shape kept,
  and `GenericDataType` reads a page's whole remaining buffer as one blob and writes it back
  verbatim. Nothing is decoded, so any key and value type survives. **[src]**
- Production's proxy log: 3 769 MB, 129 299 chunks, 42 % full → 755 MB, 1 181 chunks, in
  17–20 s; every key and value of the 711 maps, 2 257 170 entries and 1 376 MB of records,
  byte-identical, read back as UMN opens them. **[verified 2026-11-07, 1.4.198]**
- The two-argument `compact(file, compress)` swaps the copy in with `File.renameTo()`, atomic on
  Linux; if that fails, it deletes the original before moving the copy in, a window that
  `compactCleanUp()` repairs on the next open. **[src]**
- Nothing is synced to disk: `FileStore.close()` never calls `force()`, so after a power cut a
  renamed-in copy may lack its data. **[src]**
- H2 1.4.197 reads what 1.4.198 writes, compacted or not: the `umn-proxy-server:0.12` image
  (1.4.197) and `0.17` (1.4.198), each on production's log and on its compacted copy, report
  the same 710 users and log sizes, stream six users' whole logs (2 to 279 301 records) with
  the same bytes, take writes, and log no error. **[verified 2026-11-07, 1.4.197 and 1.4.198]**

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

## R_mvstore_object_array_keys — an `Object[]` key is native, ordered part by part

- `ObjectDataType` writes an `Object[]` as `TYPE_ARRAY`, its component class's index in
  `COMMON_CLASSES` (`Object` is 8), the length, then each element under its own type tag; only a
  component class outside `COMMON_CLASSES` is written by name. Read back, it is an `Object[]`.
  **[src]**, **[verified 2026-11-08, 1.4.198]**
- Two arrays compare element by element through `ObjectDataType`, then the shorter first.
  Elements of different types order by type id (`Integer` 4 before `Long` 5), not by value. **[src]**
- A map's metadata holds only `name`, `createVersion` and `type` (`MVMap.asString()`); its key and
  value `DataType`s live only in the code that opens it. A `DataType` that writes the same bytes
  reads the same file. **[src]**
- Within one open store, a map takes the `DataType` of whoever opens it first. A later `openMap()`
  with another `DataType` class gets that map, and trips an `assert` under `-ea`
  (`MVStore.openMap()`): a test reading a UMN map raw then breaks the UMN. **[src]**,
  **[verified 2026-11-08, 1.4.198]**
- `ObjectDataType`'s one field caches the last type seen; each type re-checks `instanceof` and
  falls back, so a race on it costs a lookup, never a wrong answer. **[src]**

## R_mvstore_store_per_map — a stored version is consistent per map, not across maps

- `storeNow()` takes each open map's root in turn (`setWriteVersion()`) while other threads keep
  writing, so a stored chunk may hold a later write to one map without an earlier write to
  another. **[src]**
- A map's root is a copy-on-write snapshot: within one map, a stored write implies every earlier
  write to it. **[src]**
- `commit()` stores, under `storeLock`, every write made before the call; a later chunk holds them
  too. A no-op on an in-memory store; throws on a read-only one only with unsaved writes. **[src]**
