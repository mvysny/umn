package com.gitlab.mvysny.umn.proxy

import org.h2.mvstore.MVStore
import org.h2.mvstore.MVStoreTool
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/**
 * Rewrites an MVStore file, closed, into a fresh copy holding only its live pages, and swaps the copy in:
 *
 * ```
 * StartupCompaction.compact(File(dbdir, "umn_proxy_log"))   // before anything opens the store
 * ```
 *
 * It never throws: when the disk is short, or anything fails, the file stays as it was and the cause is logged. See
 * `D_compact_on_startup`.
 *
 * ## Implementation notes
 *
 * The copy is `MVStoreTool.compact()`'s byte-exact passthrough (`R_mvstoretool_compact`); H2 syncs nothing, so the
 * copy is forced to disk before the atomic rename, and the directory after it.
 */
internal object StartupCompaction {
    /**
     * Compacts [file] in place, if it exists; a `.compacting` copy an interrupted run left beside it is deleted first.
     *
     * @param usableSpace the free bytes on [file]'s volume; tests pass a smaller number.
     */
    fun compact(file: File, usableSpace: (File) -> Long = { it.usableSpace }) {
        val copy = File(file.path + ".compacting")
        if (copy.exists()) {
            log.warn("$copy: left by an interrupted compaction, deleting it")
            Files.delete(copy.toPath())
        }
        if (!file.exists()) return
        val t0 = System.nanoTime()
        val sizeBefore = file.length()
        try {
            MVStore.Builder().fileName(file.path).readOnly().open().use { source ->
                // an upper bound: a chunk counts as live until it is old, and leaked pages count as live too
                val needed = sizeBefore / 100 * source.currentFillRate
                val free = usableSpace(file.absoluteFile.parentFile)
                if (free < needed) {
                    log.warn("$file: not compacting it, ${free.mb} free but the copy may need ${needed.mb}")
                    return
                }
                log.info("$file: compacting ${sizeBefore.mb}, ${source.currentFillRate} % full")
                MVStore.Builder().fileName(copy.path).compress().open().use { target ->
                    MVStoreTool.compact(source, target)
                    checkSameCounts(source, target)
                }
            }
            swapIn(copy, file)
            log.info("$file: compacted ${sizeBefore.mb} to ${file.length().mb} in ${(System.nanoTime() - t0) / 1_000_000} ms")
        } catch (e: Exception) {
            log.warn("$file: compaction failed, starting on the file as it was", e)
            if (copy.exists() && !copy.delete()) log.warn("$copy: can't delete it")
        }
    }

    /**
     * The same maps, with the same entry counts, which sit in the root pages: catches a truncated copy, cheaply.
     */
    private fun checkSameCounts(source: MVStore, target: MVStore) {
        val names = source.mapNames
        check(names == target.mapNames) { "the copy has other maps: ${names.size} vs ${target.mapNames.size}" }
        for (name in names) {
            val expected = source.openMap<Any, Any>(name).sizeAsLong()
            val actual = target.openMap<Any, Any>(name).sizeAsLong()
            check(expected == actual) { "map $name: $expected entries, the copy has $actual" }
        }
    }

    private fun swapIn(copy: File, file: File) {
        try {
            Files.setPosixFilePermissions(copy.toPath(), Files.getPosixFilePermissions(file.toPath()))
        } catch (e: UnsupportedOperationException) {
            log.debug("${file.parentFile}: no POSIX permissions to carry over", e)
        }
        FileChannel.open(copy.toPath(), WRITE).use { it.force(true) }
        Files.move(copy.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        try {
            FileChannel.open(file.absoluteFile.parentFile.toPath(), READ).use { it.force(true) }
        } catch (e: IOException) {
            // Windows can't open a directory: the rename is then as durable as the OS makes it
            log.debug("${file.parentFile}: can't sync the directory", e)
        }
    }

    private val Long.mb: String get() = "${this / 1024 / 1024} MB"

    @JvmStatic
    private val log = LoggerFactory.getLogger(StartupCompaction::class.java)
}
