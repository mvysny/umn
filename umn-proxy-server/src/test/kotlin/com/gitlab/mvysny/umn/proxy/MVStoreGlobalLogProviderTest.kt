package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.sync.append
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.expect

class MVStoreGlobalLogProviderTest {
    @TempDir lateinit var dir: File
    private val providers = mutableListOf<MVStoreGlobalLogProvider>()
    @AfterEach fun close() { providers.forEach { it.close() } }

    private fun provider(compactInterval: Duration = Duration.ofMinutes(1)) =
        MVStoreGlobalLogProvider(dir.absoluteFile, compactInterval).also { providers += it }

    private val MVStoreGlobalLogProvider.fillRate: Int get() = mvstorelog.db.chunksFillRate

    /**
     * Logs of three users, interleaved in many small commits, two of them then cleared: every chunk ends about a
     * third live, so the store is well under 40 % full.
     */
    private fun MVStoreGlobalLogProvider.fillWithGarbage(): List<ByteArray> {
        val log = mvstorelog
        val db = log.db
        db.retentionTime = 0  // every chunk old at once, so compaction may touch it
        val kept = mutableListOf<ByteArray>()
        val random = Random(42)
        val users = listOf("deleted1", "kept", "deleted2")
        users.forEach { log.createOrUpdateUser(it, "pwd") }
        repeat(600) { i ->
            for (user in users) {
                val record = random.nextBytes(1000)
                log.getLogMap(user).append(record)
                if (user == "kept") kept += record
            }
            if (i % 20 == 0) db.commit()
        }
        db.commit()
        // clear() first: removeMap() alone leaves the pages counted live, see R_mvstore_removemap_leaks
        log.getLogMap("deleted1").clear()
        log.getLogMap("deleted2").clear()
        db.commit()
        assertTrue(fillRate < 40, "fill rate $fillRate %")
        return kept
    }

    private fun MVStoreGlobalLogProvider.assertKept(kept: List<ByteArray>) {
        val map = mvstorelog.getLogMap("kept")
        expect(kept.size.toLong()) { map.sizeAsLong() }
        kept.forEachIndexed { i, record -> assertContentEquals(record, map[i.toLong()]) }
    }

    @Test fun `an idle store doesn't compact by itself`() {
        val p = provider()
        p.fillWithGarbage()
        val before = p.fillRate
        // MVStore's own auto-compaction would run within a second of the last commit, then every 100 ms
        Thread.sleep(2_000)
        expect(before) { p.fillRate }
    }

    @Test fun `compact() fills the store up and keeps every record`() {
        val p = provider()
        val kept = p.fillWithGarbage()
        while (p.compact()) {}
        assertTrue(p.fillRate >= 40, "fill rate ${p.fillRate} %")
        p.assertKept(kept)
    }

    @Test fun `the timer compacts`() {
        val p = provider(Duration.ofMillis(20))
        val kept = p.fillWithGarbage()
        val deadline = System.currentTimeMillis() + 10_000
        while (p.fillRate < 40 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(p.fillRate >= 40, "fill rate ${p.fillRate} %")
        p.assertKept(kept)
    }

    @Test fun `compacting beside a writer loses nothing`() {
        val p = provider()
        val kept = p.fillWithGarbage()
        val map = p.mvstorelog.getLogMap("kept")
        val added = (0 until 2000).map { Random.nextBytes(100) }
        val writer = thread {
            added.forEachIndexed { i, record ->
                map.append(record)
                if (i % 10 == 0) p.mvstorelog.db.commit()
            }
        }
        while (writer.isAlive) p.compact()
        writer.join()
        p.assertKept(kept + added)
    }

    @Test fun `close() while the timer runs`() {
        val p = provider(Duration.ofMillis(1))
        p.fillWithGarbage()
        p.close()
        providers.clear()
        // the store reopens: close() left it consistent
        expect(listOf("deleted1", "deleted2", "kept")) { provider().mvstorelog.listUsers().sorted() }
    }
}
