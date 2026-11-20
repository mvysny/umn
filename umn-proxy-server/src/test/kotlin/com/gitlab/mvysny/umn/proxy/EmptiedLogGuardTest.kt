package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.sync.MVStoreGlobalLog
import com.gitlab.mvysny.umn.sync.append
import org.h2.mvstore.MVStore
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.expect

class EmptiedLogGuardTest {
    @TempDir lateinit var dir: File
    private val file: File get() = File(dir, "umn_proxy_log")

    /**
     * Writes a log of 100 records and compacts it, then appends as [MVStoreGlobalLogProvider] does: 100 records in one
     * commit, one of 40 KB in the next, and stops without closing, as a kill does. The store header names no chunk.
     */
    private fun writeCompactedLog() {
        val random = Random(42)
        MVStoreGlobalLog(MVStore.Builder().compress().autoCompactFillRate(0).fileName(file.path).open()).use { log ->
            log.createOrUpdateUser("user", "pwd")
            repeat(100) { log.getLogMap("user").append(random.nextBytes(100)) }
        }
        StartupCompaction.compact(file)
        val db = MVStore.Builder().compress().autoCompactFillRate(0).fileName(file.path).open()
        val log = MVStoreGlobalLog(db).getLogMap("user")
        repeat(100) { log.append(random.nextBytes(100)) }
        db.commit()
        log.append(random.nextBytes(40_000))
        db.commit()
        db.closeImmediately()
    }

    /** Cuts the file's last chunk one block short: what a kill in the middle of writing it leaves. */
    private fun cutLastChunk() {
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 4096) }
    }

    private fun records(): Long = MVStoreGlobalLog(MVStore.Builder().fileName(file.path).open()).use {
        it.getLogMap("user").sizeAsLong()
    }

    @Test fun `refuses a log whose last chunk a kill cut short, naming the byte that brings it back`() {
        writeCompactedLog()
        cutLastChunk()
        val bytes = file.readBytes()
        val e = assertFailsWith<IllegalStateException> { EmptiedLogGuard.check(file) }
        assertContentEquals(bytes, file.readBytes())
        val end = Regex("truncate -s (\\d+) ").find(e.message!!)!!.groupValues[1].toLong()
        RandomAccessFile(file, "rw").use { it.setLength(end) }
        EmptiedLogGuard.check(file)
        expect(200) { records() }  // all but the 40 KB record, whose commit the kill cut
    }

    @Test fun `the provider refuses it before compacting or opening it`() {
        writeCompactedLog()
        cutLastChunk()
        val bytes = file.readBytes()
        assertFailsWith<IllegalStateException> { MVStoreGlobalLogProvider(dir.absoluteFile) }
        assertContentEquals(bytes, file.readBytes())
    }

    @Test fun `passes a whole log`() {
        writeCompactedLog()
        EmptiedLogGuard.check(file)
        expect(201) { records() }
    }

    @Test fun `passes a log whose only chunk a kill cut, as nothing was committed`() {
        MVStore.Builder().fileName(file.path).open().apply {
            openMap<Long, ByteArray>("m")[0] = Random(42).nextBytes(40_000)
            commit()
            closeImmediately()
        }
        cutLastChunk()
        EmptiedLogGuard.check(file)
    }

    @Test fun `no file, nothing to check`() {
        EmptiedLogGuard.check(file)
    }

    @Disabled("red until the fix: design/ideas/survive-a-kill-mid-write.md")
    @Test fun `a compacted log cut short still opens with its records`() {
        writeCompactedLog()
        cutLastChunk()
        val version = MVStore.Builder().fileName(file.path).readOnly().open().use { it.currentVersion }
        assertTrue(version > 0, "H2 opens it empty")
        assertTrue(records() >= 100, "${records()} records")
    }
}
