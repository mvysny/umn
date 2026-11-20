package com.gitlab.mvysny.umn.core

import org.h2.mvstore.Chunk
import org.h2.mvstore.DataUtils
import org.h2.mvstore.MVMap
import org.h2.mvstore.MVStore
import org.h2.mvstore.MVStoreTool
import org.h2.mvstore.Page
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Drops the pages older H2s leaked from a closed MVStore file: pages a chunk counts live though no map reaches them,
 * which keep the chunk in the file for good. Backs [MvstoreUMN.dropLeakedPages]; see `D_drop_leaked_pages`.
 *
 * ## Implementation notes
 *
 * A leak shows as a chunk counting more live bytes, in meta's `chunk.` entry, than the pages a walk of every map's
 * tree, meta's included, reaches in it (`R_mvstore_197_meta_leak`). The walk reads inner pages only: a leaf's position
 * in its parent gives its chunk and length. The copy is `MVStoreTool.compact()`'s passthrough of the reachable pages
 * (`R_mvstoretool_compact`), closed, so synced, before `File.renameTo()` swaps it in: a kill leaves the old file or
 * the new one, whole. Plain `java.io`, as `java.nio.file` needs Android API 26.
 */
internal object LeakedPages {
    /**
     * The meta entry marking a file checked; 1.4.200 leaks no page, so a checked file stays clean. In meta, not in a
     * map of its own: MVStore rewrites only an open map's pages, and meta is the one always open, so the entry pins no
     * chunk (`R_mvstore_unopened_map_pins`).
     */
    const val CHECKED_KEY: String = "umn.leakedPagesChecked"

    /**
     * @param usableSpace the free bytes on the file's volume; tests pass a smaller number.
     * @return the leaked bytes the copy left behind; 0 when none, the file was checked before, or the copy failed.
     */
    fun drop(file: File, usableSpace: (File) -> Long = { it.usableSpace }): Long {
        val copy = File(file.path + ".leakcopy")
        if (copy.exists()) {
            log.warn("$copy: left by an interrupted copy, deleting it")
            if (!copy.delete()) log.warn("$copy: can't delete it")
        }
        if (!file.exists()) return 0
        try {
            val leaks = MVStore.Builder().fileName(file.path).readOnly().open().use { store ->
                if (store.metaMap.containsKey(CHECKED_KEY)) return 0
                find(store)
            }
            if (leaks.leakedBytes == 0L) {
                MVStore.Builder().fileName(file.path).compress().open().use { markChecked(it) }
                return 0
            }
            val free = usableSpace(file.absoluteFile.parentFile)
            if (free < leaks.reachableBytes) {
                log.warn("$file: ${leaks.pinnedBytes.kb} pinned by leaked pages, but only ${free.kb} free for a copy of ${leaks.reachableBytes.kb}")
                return 0
            }
            val t0 = System.nanoTime()
            val sizeBefore = file.length()
            // a second read-only open: the walk opened every map with OpaqueDataType, MVStoreTool.compact() reopens them with its own
            MVStore.Builder().fileName(file.path).readOnly().open().use { source ->
                MVStore.Builder().fileName(copy.path).compress().open().use { target ->
                    MVStoreTool.compact(source, target)
                    checkSameCounts(source, target)
                    markChecked(target)
                }
            }
            if (!copy.renameTo(file)) {
                log.warn("$file: can't rename $copy over it, keeping the file as it was")
                if (!copy.delete()) log.warn("$copy: can't delete it")
                return 0
            }
            log.info("$file: dropped ${leaks.leakedBytes} leaked bytes pinning ${leaks.pinnedBytes.kb}: " +
                    "${sizeBefore.kb} to ${file.length().kb} in ${(System.nanoTime() - t0) / 1_000_000} ms")
            return leaks.leakedBytes
        } catch (e: Exception) {
            log.warn("$file: can't drop its leaked pages, opening it as it is", e)
            if (copy.exists() && !copy.delete()) log.warn("$copy: can't delete it")
            return 0
        }
    }

    /**
     * @property leakedBytes counted live, reached by no map.
     * @property pinnedBytes the length of the chunks holding them.
     * @property reachableBytes every page a map reaches: what a copy needs, at most.
     */
    class Leaks(val leakedBytes: Long, val pinnedBytes: Long, val reachableBytes: Long)

    fun find(store: MVStore): Leaks {
        // chunk id → the bytes of the reachable pages in it, each counted as Chunk.maxLenLive counts it
        val reached = HashMap<Int, Long>()
        fun add(pos: Long) {
            val chunk = DataUtils.getPageChunkId(pos)
            reached[chunk] = (reached[chunk] ?: 0L) + DataUtils.getPageMaxLength(pos)
        }
        fun walk(page: Page) {
            if (page.pos != 0L) add(page.pos)
            if (page.isLeaf) return
            for (i in 0 until page.rawChildPageCount) {
                val pos = page.getChildPagePos(i)
                when {
                    pos == 0L -> {}
                    DataUtils.getPageType(pos) == DataUtils.PAGE_TYPE_LEAF -> add(pos)
                    else -> walk(page.getChildPage(i))
                }
            }
        }
        for (name in store.mapNames) {
            walk(store.openMap(name, MVMap.Builder<Any, Any>().keyType(OpaqueDataType).valueType(OpaqueDataType)).rootPage)
        }
        walk(store.metaMap.rootPage)

        var leaked = 0L
        var pinned = 0L
        for (key in store.metaMap.keyIterator("chunk.")) {
            if (!key.startsWith("chunk.")) break
            val chunk = Chunk.fromString(store.metaMap[key])
            val extra = chunk.maxLenLive - (reached[chunk.id] ?: 0L)
            if (extra > 0) {
                leaked += extra
                pinned += chunk.len.toLong() * BLOCK_SIZE
            }
        }
        return Leaks(leaked, pinned, reached.values.sum())
    }

    private fun markChecked(store: MVStore) {
        store.metaMap[CHECKED_KEY] = System.currentTimeMillis().toString()
        // a write to meta alone leaves the store unchanged, and close() stores nothing; this marks meta changed
        store.storeVersion = store.storeVersion
    }

    /** The same maps, with the same entry counts, which sit in the root pages: catches a truncated copy, cheaply. */
    private fun checkSameCounts(source: MVStore, target: MVStore) {
        val names = source.mapNames
        check(names == target.mapNames) { "the copy has other maps: ${names.size} vs ${target.mapNames.size}" }
        for (name in names) {
            val expected = source.openMap<Any, Any>(name).sizeAsLong()
            val actual = target.openMap<Any, Any>(name).sizeAsLong()
            check(expected == actual) { "map $name: $expected entries, the copy has $actual" }
        }
    }

    // MVStore.BLOCK_SIZE, package-private: a chunk's len counts these
    private const val BLOCK_SIZE = 4096L

    private val Long.kb: String get() = "${this / 1024} KB"

    @JvmStatic
    private val log = LoggerFactory.getLogger(LeakedPages::class.java)
}
