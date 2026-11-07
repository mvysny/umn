package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.sync.MVStoreGlobalLog
import com.gitlab.mvysny.umn.sync.append
import org.h2.mvstore.MVStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.expect

class StartupCompactionTest {
    @TempDir lateinit var dir: File
    private val file: File get() = File(dir, "umn_proxy_log")
    private val leftover: File get() = File(dir, "umn_proxy_log.compacting")

    /**
     * Writes a store shaped like production's: two users' logs interleaved in small commits, then one log removed the
     * way `deleteUser()` did before 0.18, its pages leaked.
     */
    private fun writeStore() {
        val db = MVStore.Builder().compress().autoCompactFillRate(0).fileName(file.path).open()
        MVStoreGlobalLog(db).use { log ->
            val users = listOf("kept", "leaked")
            users.forEach { log.createOrUpdateUser(it, "pwd") }
            val random = Random(42)
            repeat(600) { i ->
                users.forEach { log.getLogMap(it).append(random.nextBytes(1000)) }
                if (i % 20 == 0) db.commit()
            }
            db.removeMap(log.getLogMap("leaked"))
        }
    }

    /** Every map's every entry, values as hex: what must survive a compaction. */
    private fun contents(): Map<String, List<String>> =
        MVStore.Builder().fileName(file.path).readOnly().open().use { db ->
            db.mapNames.associateWith { name ->
                db.openMap<Any, Any>(name).entries.map { (k, v) ->
                    "$k=${if (v is ByteArray) v.joinToString("") { "%02x".format(it) } else v}"
                }
            }
        }

    @Test fun `compacts the file, keeping every entry`() {
        writeStore()
        val before = contents()
        val sizeBefore = file.length()
        StartupCompaction.compact(file)
        assertTrue(file.length() < sizeBefore / 2, "${file.length()} vs $sizeBefore")
        expect(before) { contents() }
        assertFalse(leftover.exists())
    }

    @Test fun `deletes a copy an interrupted run left`() {
        writeStore()
        val before = contents()
        leftover.writeText("half a copy")
        StartupCompaction.compact(file)
        assertFalse(leftover.exists())
        expect(before) { contents() }
    }

    @Test fun `too little free disk leaves the file alone`() {
        writeStore()
        val bytes = file.readBytes()
        StartupCompaction.compact(file) { 0L }
        assertContentEquals(bytes, file.readBytes())
        assertFalse(leftover.exists())
    }

    @Test fun `a failing compaction leaves the file alone`() {
        file.writeText("not an MVStore")
        StartupCompaction.compact(file)
        expect("not an MVStore") { file.readText() }
        assertFalse(leftover.exists())
    }

    @Test fun `no file, nothing to do`() {
        StartupCompaction.compact(file)
        assertFalse(file.exists())
    }

    @Test fun `the provider compacts on startup unless told not to`() {
        writeStore()
        MVStoreGlobalLogProvider(dir.absoluteFile, compactOnStartup = false).close()
        val untouched = file.length()
        MVStoreGlobalLogProvider(dir.absoluteFile).close()
        assertTrue(file.length() < untouched / 2, "${file.length()} vs $untouched")
    }
}
