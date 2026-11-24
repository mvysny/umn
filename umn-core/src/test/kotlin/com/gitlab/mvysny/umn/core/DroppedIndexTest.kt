package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVMap
import org.h2.mvstore.MVStore
import org.h2.mvstore.WriteBuffer
import org.h2.mvstore.type.DataType
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectOutputStream
import java.io.Serializable
import java.nio.ByteBuffer
import java.util.*
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.expect

/**
 * An app's custom index key class, from before [CompositeKey]. [GoneKeyWriter] stores it under [GONE_KEY], a name no
 * class has: the class deleted, renamed or obfuscated since.
 */
private data class LiveKey(val category: String, val ordinal: Int) : Serializable, Comparable<LiveKey> {
    override fun compareTo(other: LiveKey): Int = compareValuesBy(this, other, { it.category }, { it.ordinal })
}

private val GONE_KEY = LiveKey::class.java.name.replace("LiveKey", "GoneKey")

/**
 * Writes a [LiveKey] as `ObjectDataType` writes any `Serializable`, but naming [GONE_KEY]; reads nothing back.
 */
private object GoneKeyWriter : DataType {
    // ObjectDataType.TYPE_SERIALIZED_OBJECT, package-private
    private const val TYPE_SERIALIZED_OBJECT: Byte = 19

    override fun compare(a: Any?, b: Any?): Int = (a as LiveKey).compareTo(b as LiveKey)

    override fun getMemory(obj: Any?): Int = 100

    override fun write(buff: WriteBuffer, obj: Any?) {
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(obj) } }.toByteArray()
        val live = LiveKey::class.java.name.toByteArray()
        val at = (0..bytes.size - live.size).first { i -> live.indices.all { bytes[i + it] == live[it] } }
        // same length, so the stream's structure stays intact
        GONE_KEY.toByteArray().copyInto(bytes, at)
        buff.put(TYPE_SERIALIZED_OBJECT).putVarInt(bytes.size).put(bytes)
    }

    override fun write(buff: WriteBuffer, obj: Array<out Any?>, len: Int, key: Boolean) {
        for (i in 0 until len) write(buff, obj[i])
    }

    override fun read(buff: ByteBuffer): Any? = throw UnsupportedOperationException("write-only")

    override fun read(buff: ByteBuffer, obj: Array<Any?>, len: Int, key: Boolean): Unit =
        throw UnsupportedOperationException("write-only")
}

/**
 * [IndexingUMN] clearing a dropped index whose key class is gone, through [MvstoreUMN.dropUtilityMap].
 */
class DroppedIndexTest {
    private val file: String = File.createTempFile("dropped-index", ".mv").absolutePath

    /**
     * Writes index `gone` of [entries] keyed by [LiveKey]s stored as [GONE_KEY], interleaved in the same chunks with as
     * many entries of map `kept`: a chunk wholly dead would leave [MVStore.getChunksFillRate]'s count.
     *
     * @param recorded whether `INDEX_STATE` records `gone` built; a store 0.17 or older last opened records no index.
     * @return the depth of `gone`'s page tree.
     */
    private fun writeDroppedIndex(entries: Int, recorded: Boolean = true): Int =
        MVStore.Builder().fileName(file).autoCommitDisabled().open().use { store ->
            if (recorded) store.openMap<String, Boolean>("__INDEX_STATE")["gone"] = true
            val gone = store.openMap("__INDEX2_gone", MVMap.Builder<LiveKey, Array<UUID>>().keyType(GoneKeyWriter))
            val kept = store.openMap<String, Array<UUID>>("kept")
            repeat(entries) {
                gone[LiveKey("category", it)] = arrayOf(UUID.randomUUID())
                kept["kept $it".padEnd(100, '.')] = arrayOf(UUID.randomUUID())
            }
            generateSequence(gone.rootPage) { if (it.isLeaf) null else it.getChildPage(0) }.count()
        }

    private fun expectKeysUnloadable() {
        MVStore.Builder().fileName(file).readOnly().open().use { store ->
            val e = assertFailsWith<IllegalStateException> {
                store.openMap("__INDEX2_gone", MVMap.Builder<Any, Any>().keyType(CompositeKeyDataType()))
            }
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is ClassNotFoundException }, e.toString())
        }
    }

    private fun expectDroppedAndFreed(entries: Int) {
        val store = MVStore.Builder().fileName(file).autoCommitDisabled().open()
        // else every chunk counts as young, wholly live
        store.retentionTime = 0
        val before = store.chunksFillRate
        MvstoreUMN(store, JavaDocumentSerializer, AutoTypeResolver).use { storage ->
            IndexingUMN(storage, setOf())
            expect(null) { storage.openUtilityMap<String, Boolean>("INDEX_STATE").find("gone") }
            // the chunks count the drop once stored, as any write
            store.commit()
            val after = store.chunksFillRate
            expect(false) { store.hasMap("__INDEX2_gone") }
            expect(entries) { store.openMap<String, Array<UUID>>("kept").size }
            println("fill rate before $before %, after $after %")
            assertTrue(before > 90, "fill rate before the drop: $before %")
            assertTrue(after < 70, "fill rate after the drop: $after %, the dropped index's pages still counted live")
        }
    }

    @Test fun `a dropped index of three levels is cleared and freed, its key class gone`() {
        // 48 keys a page
        expect(3) { writeDroppedIndex(5_000) }
        expectKeysUnloadable()
        expectDroppedAndFreed(5_000)
    }

    @Test fun `a dropped index of a single leaf is cleared and freed, its key class gone`() {
        expect(1) { writeDroppedIndex(10) }
        expectKeysUnloadable()
        expectDroppedAndFreed(10)
    }

    @Test fun `a dropped index that no state records is cleared and freed, its key class gone`() {
        expect(3) { writeDroppedIndex(5_000, recorded = false) }
        expectKeysUnloadable()
        expectDroppedAndFreed(5_000)
    }
}
