package com.gitlab.mvysny.umn.core

import org.h2.mvstore.Chunk
import org.h2.mvstore.DataUtils
import org.h2.mvstore.MVStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.expect

class LeakedPagesTest {
    @TempDir
    lateinit var dir: File
    private val file get() = File(dir, "db")

    /** Ten commits, each rewriting every entry, so the older chunks hold nothing a map reaches. */
    private fun write() {
        MVStore.Builder().fileName(file.path).compress().open().use { store ->
            val data = store.openMap<Int, String>("ROOT2")
            val index = store.openMap<String, Int>("__INDEX2_text")
            repeat(10) { commit ->
                for (i in 0 until 200) {
                    data[i] = "item $i, version $commit ".repeat(5)
                    index["item $i"] = commit
                }
                store.commit()
            }
        }
    }

    /**
     * Leaves [file] as 1.4.197 left production's stores (`R_mvstore_197_meta_leak`): its oldest chunk counts one more
     * page live, of 512 bytes, than any map reaches.
     */
    private fun leak() {
        MVStore.Builder().fileName(file.path).open().use { store ->
            val key = store.metaMap.keyIterator("chunk.").next()
            val entry = DataUtils.parseMap(store.metaMap[key])
            val chunk = Chunk.fromString(store.metaMap[key])
            entry["liveMax"] = java.lang.Long.toHexString(chunk.maxLenLive + 512)
            entry["livePages"] = Integer.toHexString((entry["livePages"] ?: entry["pages"]!!).toInt(16) + 1)
            store.metaMap[key] = buildString { entry.forEach { (k, v) -> DataUtils.appendMap(this, k, v) } }
            // a write to meta alone leaves the store unchanged, and the commit stores nothing
            store.openMap<Int, String>("ROOT2").let { it[0] = it[0] }
            store.commit()
        }
    }

    private fun contents(): Map<String, List<Pair<Any, Any>>> = MVStore.Builder().fileName(file.path).readOnly().open().use { store ->
        listOf("ROOT2", "__INDEX2_text").associateWith { name -> store.openMap<Any, Any>(name).entries.map { it.key to it.value } }
    }

    private fun leaks(): LeakedPages.Leaks = MVStore.Builder().fileName(file.path).readOnly().open().use { LeakedPages.find(it) }

    private fun checked(): Boolean = MVStore.Builder().fileName(file.path).readOnly().open().use { it.metaMap.containsKey(LeakedPages.CHECKED_KEY) }

    @Test
    fun `a store 1_4_200 wrote has no leak`() {
        write()
        expect(0L) { leaks().leakedBytes }
    }

    @Test
    fun `the copy drops the leaked page and keeps every entry`() {
        write()
        leak()
        val before = contents()
        expect(512L) { leaks().leakedBytes }

        expect(512L) { MvstoreUMN.dropLeakedPages(file) }
        expect(0L) { leaks().leakedBytes }
        expect(before) { contents() }
        assertTrue(checked())
        assertFalse(File(dir, "db.leakcopy").exists())
    }

    @Test
    fun `a checked file is opened read-only and left alone`() {
        write()
        leak()
        MvstoreUMN.dropLeakedPages(file)
        val bytes = file.readBytes()

        expect(0L) { MvstoreUMN.dropLeakedPages(file) }
        assertContentEquals(bytes, file.readBytes())
    }

    @Test
    fun `a clean store is marked checked, not copied`() {
        write()
        val before = contents()

        expect(0L) { MvstoreUMN.dropLeakedPages(file) }
        assertTrue(checked())
        expect(before) { contents() }
    }

    @Test
    fun `too little disk for the copy leaves the file as it was, to retry`() {
        write()
        leak()
        val bytes = file.readBytes()

        expect(0L) { LeakedPages.drop(file) { 0L } }
        assertContentEquals(bytes, file.readBytes())
        assertFalse(File(dir, "db.leakcopy").exists())
        expect(512L) { MvstoreUMN.dropLeakedPages(file) }
    }

    @Test
    fun `a copy a kill interrupted is deleted, and the copy redone`() {
        write()
        leak()
        File(dir, "db.leakcopy").writeText("half a copy")

        expect(512L) { MvstoreUMN.dropLeakedPages(file) }
        assertFalse(File(dir, "db.leakcopy").exists())
    }

    @Test
    fun `no file, nothing to do`() {
        expect(0L) { MvstoreUMN.dropLeakedPages(file) }
        assertFalse(file.exists())
    }
}
