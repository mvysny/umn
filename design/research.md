# Research — H2 MVStore

What the things we don't own actually do. About *them*, never us: a sentence starting "we chose"
is a `D_`. `## R_<slug> — <title>`, one claim per bullet, one provenance marker per claim —
**[docs]**, **[src]**, **[verified <date>, <version>]**, **[unverified]** (a hypothesis; a design
built on it says so). A claim is earned by its provenance, or by having cost real work to find
out. Checked against h2-mvstore 1.4.200 unless a claim names another version. Cite by
slug, `R_<slug>`, never by position; `grep '^## R_' design/research.md` is the index. When you
have written an entry, re-read it against the one above for consistency; a marker already says
where a claim came from, so the prose doesn't repeat it.

---

## R_mvstore_idle_compaction — the background writer walks every chunk on its wake-ups

- The background writer wakes every `autoCommitDelay / 10` ms, 100 by default. A wake-up after
  file I/O calls `getProjectedFillRate()`, which walks every chunk, whatever `autoCompactFillRate`;
  one with none runs `doMaintenance()`, which walks them only with the file over
  `autoCompactFillRate` full. **[src]**
- `autoCompactFillRate` defaults to 90 (its javadoc says 40). Projected under it, the writer
  rewrites and moves chunks itself; at 0 it rewrites nothing, yet still moves chunks once the free
  space is fragmented (`isFragmented()`). A store committed in small writes grows a chunk a
  commit. **[src]**
- 100 000 chunks, `autoCompactFillRate(0)`: idle, 1.5 ms CPU a second, as an empty store; a commit
  every 200 ms, 6–10 ms a second, nearly all in `getProjectedFillRate()`, 1–2 ms a walk; once, at
  start, 1.2–1.7 s moving chunks. The default builder compacts that store itself within seconds.
  **[verified 2026-11-20, 1.4.200]**
- 1.4.198 walks every chunk on every wake-up, I/O or not (`compact()` → `getCurrentFillRate()`).
  **[src]** Idle, production's 129 299-chunk log took 5.5 % of a CPU, 7.6 % on the droplet's; with
  `autoCompactFillRate(0)`, 0.1 %. **[verified 2026-11-07, 1.4.198]**

## R_mvstore_store_growth — how far a node's store outgrows its live data

- Steady phone-paced edits: 1.4–2.4× the live data on 1.4.200, 3.0–4.9× on 1.4.198. Ten
  production users' logs, 20 000 edits each through Aedict's own stack and sync, MVStore's timers
  and the pauses scaled 100×. **[verified 2026-11-20, 1.4.198 and 1.4.200]**
- `autoCommitBufferSize` sets how often a burst stores a chunk; H2's default is
  `min(19, 64 × max heap / 1 GB)` MB, 16 MB on a 256 MB heap. **[src]** The same ten whole logs
  pulled as a first sync: 144–509 s at Aedict's 256 KB, peaking at 28–375× live; 17–42 s at
  16 MB, 8–39×. With MVStore's timers 10× faster, as on a slower phone: 8–85× and 9–152×, worst
  the stores under 1 MB live, at most 350 MB. **[verified 2026-11-20, 1.4.200]**
- A chunk emptied at a commit is freed only 45 s and `versionsToKeep` (5) commits later. Idle, the
  writer commits only to rewrite or move chunks, neither while the file is over 90 % full and
  `getProjectedFillRate()`, blind to dead chunks, sees no gain. **[src]** So a pull's last chunks
  stay: at a phone's pace, 20 idle minutes on, dead chunks filled 29–95 % of the files, at
  1.9–22× live; 6 commits later, 1.0–2.8× at 16 MB and 1.7–2.6× at 256 KB.
  **[verified 2026-11-20, 1.4.200]**
- Idle, a store under 90 % full is never rewritten: the writer only moves chunks into free space,
  `autoCommitMemory × 90 / fillRate` bytes a wake-up, and never a chunk larger than that
  (`findChunksToMove()`). **[src]** Production's 67 web replicas over 1 MB, 1 018 MB for 192 MB
  live, two idle minutes and 6 commits on: 519 MB at 256 KB, median 2.40×, 24 of the 25 stores
  over 3× after the idle holding a chunk over the budget; 431 MB at 16 MB, median 1.13×, no chunk
  over it. 1 021 MB on 1.4.198. **[verified 2026-11-20, 1.4.198 and 1.4.200]**

## R_mvstore_removemap_leaks — 1.4.198's `removeMap()` left the map's pages counted live

- 1.4.198's `removeMap()` dropped the map's metadata and closed it, never marking its pages
  removed: their space was never reclaimed. **[src]**, **[verified 2026-11-07, 1.4.198]**
- 1.4.200's `removeMap(map)` clears the map first (`clearIt()`). Three interleaved maps, two
  removed: chunks 61 % full → 20 % at the next commit, the same as with `clear()` first; compaction
  then brings it to 98 %. **[src]**, **[verified 2026-11-20, 1.4.200]**
- `clear()` is cheap: it swaps in an empty root, then reads only the inner pages, freeing an
  unloaded leaf by its position alone. **[src]**
- Space 1.4.198 leaked comes back only offline, from `MVStoreTool.compact()`
  (`R_mvstoretool_compact`): on production's proxy log, 1.47 GB was counted live but 755 MB
  reachable, so ~0.7 GB was the deleted users' logs. **[verified 2026-11-07, 1.4.198]**

## R_mvstore_197_meta_leak — 1.4.197 left meta pages counted live, pinning their chunks for good

- After a commit replaced it, 1.4.197 sometimes left the old copy of the meta leaf that holds the
  maps' `map.`/`name.`/`root.` entries counted live in its chunk. 8 sessions of 20 commits on
  12 maps, each idle 1.5–3 s before its close or kill: 28 of 160 chunks. With 3 maps, meta's one
  leaf: none. Without the idle: none. 1.4.198, 1.4.199, 1.4.200: none.
  **[verified 2026-11-20, 1.4.197–1.4.200]**
- Production's web replicas, all written by 1.4.197: 20 of the 69 over 1 MB hold such chunks,
  written 2018–2026; in 14, nearly every chunk. **[verified 2026-11-20, 1.4.200]**
- Such a chunk is never dead, so never freed, and no rewrite moves a page no map reaches: it keeps
  its whole length in the file. **[src]** Those 14 at a 16 MB buffer: 1.4.200 emptied each but
  for that page, 3.1–5.6× live after 6 commits; the pinned chunks alone, 2.1–4.5×.
  **[verified 2026-11-20, 1.4.200]**
- `getProjectedFillRate()` counts them as space a rewrite would free, so the file never looks
  90 % full and the idle writer never rests. It rewrites and moves the live data, and writes the
  header and fsyncs 10 times a second. Over two idle minutes: 35 MB a minute for 0.9 MB live, 44
  for 1.3 MB, 590 for 18 MB; the clean stores write nothing. **[src]**,
  **[verified 2026-11-20, 1.4.200]**
- A walk of every map's tree and meta's finds them: chunks counting more live bytes than the pages
  reached in them. `MVStoreTool.compact()` copies only reachable pages, so the copy drops them.
  **[src]**

## R_mvstoretool_compact — `MVStoreTool.compact()`: a byte-exact offline copy

- `compact(source, target, compress)` opens the source read-only and writes a new target file,
  holding only the maps `getMapNames()` lists: a removed map's pages are left behind. **[src]**
- The copy is a passthrough: `MVMap.copyFrom()` copies the trees page by page, their shape kept,
  and `GenericDataType` reads a page's whole remaining buffer as one blob and writes it back
  verbatim. Nothing is decoded, so any key and value type survives. **[src]**
- Production's proxy log: 3 769 MB, 129 659 chunks, 42 % full → 753 MB, 593 chunks, in
  17–23 s; every key and value of the 715 maps, 2 265 385 entries and 1 381 MB of records,
  identical, read back as UMN opens them. **[verified 2026-11-20, 1.4.200]**
- The two-argument `compact(file, compress)` swaps the copy in with `File.renameTo()`, atomic on
  Linux; if that fails, it deletes the original before moving the copy in, a window that
  `compactCleanUp()` repairs on the next open. **[src]**
- `MVStore.close()` of a writable store `sync()`s the file (`closeStore()`); nothing syncs the
  directory, so after a power cut a rename may be lost. **[src]**
- H2 1.4.197 reads what 1.4.198 writes, compacted or not: the `umn-proxy-server:0.12` image
  (1.4.197) and `0.17` (1.4.198), each on production's log and on its compacted copy, report
  the same 710 users and log sizes, stream six users' whole logs (2 to 279 301 records) with
  the same bytes, take writes, and log no error. **[verified 2026-11-07, 1.4.197 and 1.4.198]**

## R_mvstore_compact_concurrency — `compact()` is safe beside concurrent writers

- With the defaults, the background writer itself rewrites chunks (`rewriteChunks()`) while other
  threads write; it is a supported, concurrent path, not a maintenance-only one. **[src]**
- The rewrite is ordinary map writes: `MVMap.rewritePage()` re-puts the key's current value
  through `operate()`, one compare-and-swap like `put`; a key removed meanwhile aborts it. **[src]**
- `compact()` takes `storeLock`, the lock every commit takes, only via `tryLock(10 ms)`, skipping a
  busy round, and releases it while it rewrites the maps: commits interleave. **[src]**
- A map removed mid-rewrite is skipped: `rewritePage()` checks `isClosed()`. **[src]**
- On a closed store that has stored a chunk, `compact()` throws `IllegalStateException`
  (`checkOpen()`). **[src]**
- A chunk the rewrite emptied is freed once `versionsToKeep` (5) later versions are stored, at
  least one; till then the fill rate counts it. So `compactFile()`, rewriting until the chunks are
  95 % full, never returns on a live store: after 30 s still rewriting, its file doubled.
  **[src]**, **[verified 2026-11-20, 1.4.200]**
- The file is `nio:` by default, a `RandomAccessFile`'s `FileChannel`: interrupting a thread inside
  its I/O closes the channel for every thread (`ClosedByInterruptException`), and the store is
  dead until reopened. **[src]**, **[docs]** (`InterruptibleChannel`)

## R_mvstore_object_array_keys — an `Object[]` key is native, ordered part by part

- `ObjectDataType` writes an `Object[]` as `TYPE_ARRAY`, its component class's index in
  `COMMON_CLASSES` (`Object` is 8), the length, then each element under its own type tag; only a
  component class outside `COMMON_CLASSES` is written by name. Read back, it is an `Object[]`.
  **[src]**, **[verified 2026-11-20, 1.4.200]**
- Two arrays compare element by element through `ObjectDataType`, then the shorter first.
  Elements of different types order by type id (`Integer` 4 before `Long` 5), not by value. **[src]**
- A map's metadata holds only `name`, `createVersion` and `type` (`MVMap.asString()`); its key and
  value `DataType`s live only in the code that opens it. A `DataType` that writes the same bytes
  reads the same file. **[src]**
- Within one open store, a map takes the `DataType` of whoever opens it first. A later `openMap()`
  with another `DataType` class gets that map, and trips an `assert` under `-ea`
  (`MVStore.openMap()`): a test reading a UMN map raw then breaks the UMN. **[src]**,
  **[verified 2026-11-20, 1.4.200]**
- `ObjectDataType`'s one field caches the last type seen; each type re-checks `instanceof` and
  falls back, so a race on it costs a lookup, never a wrong answer. **[src]**

## R_mvstore_clear_reads_no_key — `clear()` frees a map whose keys no longer deserialize

- An inner page holds its header, the child positions and counts, then the keys, last; values
  only on leaves (`Page.read()`, `Page.write()`). `clear()` needs only the positions, so a key
  type that swallows the rest of the page as one blob, `MVStoreTool.GenericDataType` say, frees
  the whole tree. **[src]**, **[verified 2026-11-20]** (`DroppedIndexTest`)
- `openMap()` reads the root page eagerly, keys and values, leaf or not: an unloadable class
  throws there, wrapped in `IllegalStateException`, before `clear()` runs. **[src]**,
  **[verified 2026-11-20]** (`DroppedIndexTest`)
- `clear()` swaps in an empty root, then `removeAllRecursive()`: no search, no `compare()`. **[src]**
- `removeMap(map)` clears and closes the map, keeping it registered until its version ages out or
  the store closes. `removeMap(name)` opens it with `ObjectDataType` unless open, then does the
  same: an unreadable map throws. A map's id is `meta["name.<name>"]`, hex; `getMap(id)` returns
  it if open. **[src]**

## R_mvstore_store_per_map — a stored version is consistent per map, not across maps

- `storeNow()` takes each open map's root in turn (`setWriteVersion()`) while other threads keep
  writing, so a stored chunk may hold a later write to one map without an earlier write to
  another. **[src]**
- A map's root is a copy-on-write snapshot: within one map, a stored write implies every earlier
  write to it. **[src]**
- The walk follows `maps.values()`, a `ConcurrentHashMap` keyed by map id, ids handed out in
  creation order: ascending while every id is below the table's size. A chunk holds the later of
  two writes to different maps without the earlier only if the walk takes the earlier-written
  map's root first, and both writes land before it takes the other's. **[src]**
- A node pulling records, 3 000 maps opened between `ROOT2` and its log pointer, a `commit()` from
  another thread, then `closeImmediately()`: the stored pointer stood 6 to 79 records past what
  `ROOT2` held, in 5 runs of 5. **[verified 2026-11-20, 1.4.200]** (`SyncingUMNTest`)
- `Builder.autoCommitDisabled()` sets only `autoCommitDelay` to 0, stopping the background writer.
  `autoCommitMemory` is final, so a write past it still stores, from the writing thread
  (`beforeWrite()`). **[src]**
- `commit()` waits for `storeLock`, as `tryCommit()` doesn't, then stores every write made before
  the call; a later chunk holds them too, and a commit finding them stored does nothing. A no-op on
  an in-memory store; throws on a read-only one only with unsaved writes. **[src]**

## R_mvstore_commit_per_write — a commit after each small write costs a chunk, ~26 KB

- 10 000 appends of 500 random bytes to a 64 MB compressed log, `autoCompactFillRate(0)`, each
  followed by `commit()`: 280–400 µs each, 10 000 chunks, the file from 64 to 329 MB. Uncommitted,
  the same appends took 19 ms and one chunk. **[verified 2026-11-20, 1.4.200]**
- Four to seven `compact(40, autoCommitMemory)` calls took the 10 499 chunks down to 740–950.
  **[verified 2026-11-20, 1.4.200]**
- A node's store, `compress()`, H2's default buffer, 30 000 documents and two indices in 8.5 MB:
  1 000 local edits back to back grew it 6.9 KB an edit, 35 KB with a commit after each. The
  commit took 0.47 ms median on a desktop, 1.4 ms (p90 3.6 ms) on API 22's emulator, 4 x86_64
  cores, and the edits 2.2× as long. At `autoCommitBufferSize(256)`, 36 KB an edit without the
  commit: a chunk every few edits anyway. **[verified 2026-11-20, 1.4.200]** (`NodeCommitCost`)
- Edits 1.5 s apart: 24 KB an edit with or without the commit, the background writer storing each
  edit on its own. The commit, cold: 0.6 ms median on a desktop, 1.4–1.7 ms on API 22's emulator,
  p90 3–11 ms. **[verified 2026-11-20, 1.4.200]**
- A commit run on another thread after each edit, the edits made meanwhile sharing it: back to
  back, 15 commits and 8.4 KB an edit on a desktop, 322 and 19 KB on API 22's emulator, in about
  the time with no commit; one took 2.8 ms median there, 42 ms at most.
  **[verified 2026-11-20, 1.4.200]**

## R_kill_vs_crash — a killed JVM loses no write; a crashed host loses the unsynced ones

- `MVStore.Builder().readOnly()` opens the file in mode `"r"` under a shared lock
  (`FileStore.open()`): the OS refuses every write. **[src]**
- A reader killed anywhere leaves the file byte-identical: of 72 SIGKILLs spread over a
  `StartupCompaction` of a 30 MB log (`KillTest`), 46 landed before its rename. **[verified
  2026-11-20, 1.4.198]**
- A killed process, SIGKILL included (`docker kill`, the OOM killer, `docker stop` past its grace
  period), loses no `write()` that returned: the next process read every chunk H2 had written,
  none of them synced. Whether H2 then finds them is `R_mvstore_kill_opens_empty`. **[verified
  2026-11-20, 1.4.198]**
- The bytes sit in the kernel's page cache, which outlives the process; only a kernel crash or a
  power cut loses what no `fsync()` forced to disk. **[docs]**
- `rename()` over an existing file replaces it atomically: no process ever finds the target
  missing, so a kill can't leave the swap half-done. **[docs]**
- After a crash, a rename is on disk only if the directory was `fsync`ed after it. **[docs]**

## R_mvstore_kill_opens_empty — a kill mid-chunk opened 1.4.198's store empty, not 1.4.200's

- Up to 1.4.199, `readStoreHeader()` takes the newer of the header's chunk and the one whose
  footer ends the file, then follows `next`; with neither, the store opens empty at version 0.
  The header names a chunk only once one is stored short of the file's end: never in a new store
  or in `MVStoreTool.compact()`'s copy. **[src]**, **[verified 2026-11-20, 1.4.198]**
- So a SIGKILL cutting the last chunk short opened 1.4.198's store empty: 5 of ~30 kills of a JVM
  committing to a new store, 1 of 20 appending to a `StartupCompaction` copy. A read-write open
  and `close()` then truncated the file to its headers. **[verified 2026-11-20, 1.4.198]**
- 1.4.200 trusts the header only when it says `clean:1`, which `close()` writes with the last
  chunk, and the first store after an open drops. Otherwise it walks back from the file's end to
  the last chunk whose header and footer agree, follows `next`, and checks every chunk the meta
  map lists; failing that, it scans every block. **[src]**
- A file a real kill left, 20 KB into a 48 KB chunk: 1.4.197 to 1.4.199 open it empty, 1.4.200
  at version 364, all 3 640 entries. Production's log with its newest chunk cut, and stores on
  Android API 22 and 24, open at the last whole commit too. **[verified 2026-11-20, 1.4.200]**
- The walk costs what 1.4.198's open did, two small reads a chunk: on production's log after a
  kill, 275 000 reads for 130 000 chunks, 1.3–3.6 s with the page cache warm; compacted, 3 000
  reads, 70 ms; after a `close()`, 12 000 and 250. **[verified 2026-11-20, 1.4.198 and 1.4.200]**

## R_mvstore_clean_flag_handover — 1.4.200 trusts a `clean:1` that 1.4.198 left stale

- 1.4.197 and 1.4.198 copy every key of the header they read into each one they write, so a
  `clean:1` survives their writes; and they rewrite it only for a chunk stored short of the
  file's end. **[src]**
- Trusting `clean:1`, 1.4.200 skips the walk from the file's end and follows `next` from the
  header's chunk only (`R_mvstore_kill_opens_empty`). **[src]**
- 1.4.200 closes a store, 1.4.198 commits 200 times into it with space reuse and closes it,
  1.4.200 reopens: 5 of 14 runs ending in chunks no hole fits opened 2 to 8 versions short,
  silently, while 1.4.198 read them all. With 1.4.200 closing by `commit()`, `sync()` and
  `closeImmediately()`, never writing `clean:1`, 13 of 13 read everything.
  **[verified 2026-11-20, 1.4.198 and 1.4.200]**

## R_h2_1_4_200_drop_in — 1.4.200 writes 1.4.198's format and runs on Android API 22

- `FORMAT_WRITE` and `FORMAT_READ` stay 1, `ObjectDataType` differs by a license URL, and
  `Page.write()` lays a page out as before. Chunk metadata adds `unusedAtVersion` and `pinCount`,
  the header `clean`; 1.4.197 parses both as maps and skips keys it doesn't know. **[src]**
- Both jars are Java 7 bytecode. Of the Java APIs 1.4.200 calls and 1.4.198 didn't, API 22 has
  all but `GarbageCollectorMXBean.getMemoryPoolNames()`, which only `Utils.collectGarbage()`
  calls, and nothing in h2-mvstore calls that. **[src]**
- `MvstoreUMN` under `dalvikvm` on API 22 creates, commits, reopens and reads; Aedict on API 24,
  installed over its 1.4.198 build, opens the old store and writes to it; no `VerifyError`.
  **[verified 2026-11-20, 1.4.200]**
- 2.0.202's MVStore fixes miss a plain-map store: #3097 is in `rollbackTo()`; #2967 needs a
  Java-serialized object over ~72 MB; #3066 is the SQL layer's LOB map, absent from h2-mvstore;
  #2972, page memory drifting negative, needs Java-serialized values and costs cache, not data.
  **[src]** (the issues' fix diffs against 1.4.200)

## R_h2_reads_one_format — 1.4 and 2.x each open only their own file format

- 1.4.x reads and writes format 1 only (`MVStore`'s `FORMAT_READ`, `FORMAT_WRITE`); a new file's
  header says `format:1`. **[verified 2026-11-20, 1.4.200]**
- 2.4.240 reads and writes format 3 only (`FileStore`'s `FORMAT_READ_MIN`/`_MAX`,
  `FORMAT_WRITE_MIN`/`_MAX`). **[verified 2026-11-20, 2.4.240]**
- Neither opens the other's file, read-only included: 2.4.240 on a 1.4.200 file throws
  `MVStoreException: The read format 1 is smaller than the supported format 3`; 1.4.200 on a
  2.4.240 file, `IllegalStateException: The read format 3 is larger than the supported format 1`.
  **[verified 2026-11-20, 1.4.200 and 2.4.240]**
- Which format a file is, the first line of its header says in plain text (`…,format:1,…`):
  readable without H2. **[verified 2026-11-20, 1.4.200 and 2.4.240]**
- Both put MVStore in `org.h2.mvstore`, so one class loader holds one of them; a process reading
  1.4 and writing 2.x needs one copy relocated. **[verified 2026-11-20, 1.4.200 and 2.4.240]**
- H2's own way across, 2.x's `org.h2.tools.Upgrade`, is for SQL databases only: it downloads the
  old version's jar from Maven Central at runtime, dumps the database with `SCRIPT TO` and
  reloads it with `RUNSCRIPT FROM`. **[src]** (2.4.240's class constants)
