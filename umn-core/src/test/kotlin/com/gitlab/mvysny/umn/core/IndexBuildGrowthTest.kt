package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVStore
import org.h2.mvstore.MVStoreTool
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.Serializable
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * Building an index over a store opened as Aedict opens it, `compress()` and `autoCommitBufferSize(256)`: MVStore then stores
 * a chunk every 256 KB of changes, and entries written in document order rewrote nearly the whole index at each.
 */
class IndexBuildGrowthTest {
    data class Note(val category: String, val position: Int, val text: String) : Serializable

    private val byCategory = IndexingUMN.IndexDef.simple<CompositeKey, Note>("category",
        { setOf(CompositeKey.of(it.category, it.position)) }, Note::class)
    private val byWord = IndexingUMN.IndexDef.simple<CompositeKey, Note>("word",
        { note -> note.text.split(' ').take(2).map { CompositeKey.of(it, note.category) }.toSet() }, Note::class)

    @Test fun `two indices over 30 000 documents don't grow the store several times over`(@TempDir dir: File) {
        val file = File(dir, "db")
        val store = MVStore.Builder().compress().autoCommitBufferSize(256).fileName(file.path).open()
        val storage = MvstoreUMN(store, JavaDocumentSerializer, AutoTypeResolver)
        val random = Random(1)
        val words = List(500) { List(2 + random.nextInt(4)) { 'a' + random.nextInt(26) }.joinToString("") }
        // in ID order, so the documents alone leave the file near their live size
        List(30_000) { UUID(random.nextLong(), random.nextLong()) }.sorted().forEachIndexed { i, id ->
            storage.create(Note("category${random.nextInt(40)}", i, List(20) { words.random(random) }.joinToString(" ")), id)
        }
        store.commit()
        val before = file.length()

        val start = System.currentTimeMillis()
        IndexingUMN(storage, setOf(byCategory, byWord))
        val after = file.length()
        val millis = System.currentTimeMillis() - start
        store.close()
        val live = File(dir, "live").also { MVStoreTool.compact(file.path, it.path, true) }.length()
        println("Building 2 indices over 30 000 documents: ${before / 1024} KB -> ${after / 1024} KB in $millis ms; live ${live / 1024} KB")
        assertTrue(after < 2 * live, "the store grew from ${before / 1024} KB to ${after / 1024} KB, for ${live / 1024} KB live")
    }
}
