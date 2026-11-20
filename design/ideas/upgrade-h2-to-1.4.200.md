# Upgrade H2 to 1.4.200

The owner, 2026-11-20, after `survive-a-kill-mid-write.md`: does a newer H2 fix the kill that
opens a store empty (`R_mvstore_kill_opens_empty`)? Only 1.4.x can stay, `D_frozen_0_15_formats`:
1.4.200 is the last, 2019-10-14; 2.x writes another format. 0.15 runs 1.4.197.

## What 1.4.200 fixes

- Its changelog: PR #2049 "mvstore data lost issue when partial write occurs", #1812, #1961
  "Failure to open DB after improper shutdown", #2046 "Recovery mode", #2132 "another bug in
  MVStore.readStoreHeader()". 1.4.199 lists nothing on MVStore recovery.
- `readStoreHeader()` trusts the header only when it says `clean:1`. Otherwise `discoverChunk()`
  walks back from the file's end to the last chunk whose header and footer agree, and failing
  that, every block of the file. `close()` writes the header naming the last chunk with `clean:1`
  and syncs; the first store after an open drops `clean` and rewrites the header; so does a
  compaction before it moves chunks.
- The file a real kill left, opened read-only: 1.4.197, 1.4.198 and 1.4.199 open it empty, 1.4.200
  at version 364 with all 3 640 entries. On 1.4.200, `EmptiedLogGuardTest`'s disabled reproducer
  passes, and the guard has nothing left to refuse.

## What the checks found, 2026-11-20

**The build.** Three APIs are gone: `compactRewriteFully()` (`MvstoreUMN.compact()`: 1.4.200's
`compactFile()` then `compactMoveChunks()`), `getCurrentFillRate()` (`getChunksFillRate()`, the
same chunk-based measure: `StartupCompaction`, three tests), and `DataUtils.parseChecksummedMap()`,
now package-private (`EmptiedLogGuard`; `parseMap()` and `getFletcher32()` stay public). Then 682
tests, 6 failing, none a regression:

- `EmptiedLogGuardTest` ×2: nothing to refuse, above.
- `MVStoreGlobalLogProviderTest` ×2, the compaction timer: 1.4.200 frees the chunks a compaction
  emptied only once later versions are stored. One `compact()` rewrote everything; the chunk fill
  rate stayed 13 % until the 6th small commit, then 81 %. The tests measure without committing; a
  live proxy commits all the time.
- `DroppedIndexTest` ×2: an unloadable key now throws `IllegalStateException`, not
  `IllegalArgumentException`. With that accepted, `clear()` still frees the dropped index without
  reading its keys: fill rate 100 % → 53 % and 43 %.
- `umn-compat-0.15` all green: 0.15 nodes and the production server image sync with HEAD; 0.15
  opens what HEAD wrote (F1–F4) and HEAD what 0.15 wrote (B1–B4); the 0.12 server takes back
  HEAD's compacted log (G2).

**The format.** `FORMAT_WRITE` and `FORMAT_READ` stay 1. `ObjectDataType` differs by one line, a
license URL. `Page.write()` lays a page out as before. Chunk metadata adds `unusedAtVersion` and
`pinCount`, and the header `clean`; 1.4.197 parses maps and ignores unknown keys.

**The `clean` flag across versions.** On `clean:1`, 1.4.200 skips the walk from the file's end and
only follows `next` from the header's chunk. 1.4.197 keeps the key while it appends after HEAD
closed the file. Three such handovers, 1.4.197 committing mixed, large, then small-then-large
records: 1.4.200 read every entry each time, as 1.4.197 appended where the chain predicted. Not
reproduced: 1.4.197 storing a chunk off the chain without rewriting the header, which would open
at HEAD's last close and lose what 0.15 wrote. Removable: HEAD closes with `commit()`, `sync()`,
`closeImmediately()`, never leaving `clean:1`, at the price of the full walk on every open.

**Android, minSdk 22.** Both jars are Java 7 bytecode. Of the Java APIs 1.4.200 calls and 1.4.198
didn't, all are in API 22 but `GarbageCollectorMXBean.getMemoryPoolNames()`, which only
`Utils.collectGarbage()` calls, and nothing in h2-mvstore calls that. `scaleForAvailableMemory()`
reaches `java.lang.management` as 1.4.198 did. Not run on a device.

**Bugs fixed only in 2.x.** 2.0.202's changelog names no MVStore bug of 1.4.200's; its MVStore fixes
are mostly the transaction store, LOBs and 2PC, which UMN doesn't use. Unclear for plain maps:
#3097 "Possible MVStore compaction issue", #2972 negative page memory, #2967 `ObjectDataType`
`averageSize` int overflow, #3066 "Very high DB opening/closing times".

## Still to do before upgrading

1. Aedict on an API 22 emulator: open, sync, kill, reopen.
2. The open after a kill now walks the file: time it on a copy of production's proxy log, with a
   cut chunk at the end. `close()` now syncs.
3. Read the four unclear 2.x fixes against 1.4.200's source.
4. Decide the `clean` flag: leave it, or never write it.
5. Re-verify the `R_` entries checked on 1.4.198 (`R_mvstore_idle_compaction`,
   `R_mvstore_removemap_leaks`, `R_mvstoretool_compact`, `R_mvstore_compact_concurrency`,
   `R_mvstore_object_array_keys`, `R_mvstore_clear_reads_no_key`, `R_mvstore_store_per_map`), and
   `research.md`'s "checked against 1.4.198".

## The change, once it holds

The pin and its comment in `libs.versions.toml`; the three API sites; the timer tests commit after
compacting; `DroppedIndexTest` takes `IllegalStateException`. `EmptiedLogGuard`, its tests and
the README section go; `R_mvstore_kill_opens_empty` says 1.4.200 opens such a file; a CHANGELOG
line. `survive-a-kill-mid-write.md`'s A is this; its C holds for nodes on HEAD only, as installed
0.15 apps keep 1.4.197.
