package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVMap
import org.h2.mvstore.MVStore
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.util.*
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.expect

class CompositeKeyTest {
    @Nested inner class Of {
        @Test fun `takes the types H2 stores natively`() {
            val uuid = UUID.randomUUID()
            val key = CompositeKey.of("a", true, 'c', 1.toByte(), 2.toShort(), 3, 4L, 5f, 6.0, BigInteger.TEN, uuid)
            expect(listOf("a", true, 'c', 1.toByte(), 2.toShort(), 3, 4L, 5f, 6.0, BigInteger.TEN, uuid)) { key.toList() }
            expect(11) { key.size }
            expect(4L) { key[6] }
        }

        @Test fun `rejects other types`() {
            assertFailsWith<IllegalArgumentException> { CompositeKey.of("a", Date()) }
            assertFailsWith<IllegalArgumentException> { CompositeKey.of(BigDecimal.ONE) }
            assertFailsWith<IllegalArgumentException> { CompositeKey.of(CompositeKey.of("a")) }
            assertFailsWith<IllegalArgumentException> { CompositeKey.of(arrayOf("a")) }
        }

        @Test fun `rejects no parts`() {
            assertFailsWith<IllegalArgumentException> { CompositeKey.of() }
        }

        @Test fun `copies the parts`() {
            val parts: Array<Any> = arrayOf("a", 1)
            val key = CompositeKey.of(*parts)
            parts[0] = "b"
            expect("a") { key[0] }
        }
    }

    @Test fun `equals and hashCode follow the parts`() {
        expect(CompositeKey.of("a", 1)) { CompositeKey.of("a", 1) }
        expect(CompositeKey.of("a", 1).hashCode()) { CompositeKey.of("a", 1).hashCode() }
        expect(false) { CompositeKey.of("a", 1) == CompositeKey.of("a", 1L) }
        expect(false) { CompositeKey.of("a") == CompositeKey.of("a", 1) }
        expect(false) { CompositeKey.of("a") == CompositeKey.of("a").prefixRange().endInclusive }
        expect("(a, 1)") { CompositeKey.of("a", 1).toString() }
        expect("(a, *)") { CompositeKey.of("a").prefixRange().endInclusive.toString() }
    }

    @Test fun `orders part by part, a prefix first`() {
        val sorted = listOf(
            CompositeKey.of("a"), CompositeKey.of("a", -1), CompositeKey.of("a", 1), CompositeKey.of("a", 1, "x"),
            CompositeKey.of("a", 2), CompositeKey.of("b"), CompositeKey.of("b", 0)
        )
        expect(sorted) { sorted.shuffled(Random(3)).sorted() }
        expect(0) { CompositeKey.of("a", 1).compareTo(CompositeKey.of("a", 1)) }
    }

    @Test fun `parts of different types sort by type, not by value`() {
        expect(true) { CompositeKey.of(5) < CompositeKey.of(3L) }
    }

    @Test fun `prefixRange holds exactly the keys starting with the prefix`() {
        val range = CompositeKey.of("b", 1).prefixRange()
        expect(true) { CompositeKey.of("b", 1) in range }
        expect(true) { CompositeKey.of("b", 1, "z") in range }
        expect(true) { CompositeKey.of("b", 1, Long.MAX_VALUE, "z") in range }
        expect(false) { CompositeKey.of("b") in range }
        expect(false) { CompositeKey.of("b", 0, "z") in range }
        expect(false) { CompositeKey.of("b", 2) in range }
        expect(false) { CompositeKey.of("c") in range }
        expect(false) { range.isEmpty() }
    }

    @Test fun `a range bound can't be stored`() {
        val umn = MvstoreUMN.inMemory(JavaDocumentSerializer, AutoTypeResolver)
        val map = umn.openUtilityMap<CompositeKey, String>("m")
        assertFailsWith<IllegalArgumentException> { map.put(CompositeKey.of("a").prefixRange().endInclusive, "x") }
    }

    @Nested inner class InAnIndexOnDisk {
        private val file = File.createTempFile("composite", ".mv").absolutePath
        // a document "a:2" is keyed ("a", 2f)
        private val idx = IndexingUMN.IndexDef.simple<CompositeKey, String>("composite",
            { doc -> setOf(CompositeKey.of(doc.substringBefore(':'), doc.substringAfter(':').toFloat())) }, String::class)
        private val keys = listOf(CompositeKey.of("a", 2f), CompositeKey.of("a", 10f), CompositeKey.of("b", 1f))

        private fun open() = IndexingUMN(createMvstoreUMN(file), setOf(idx))

        private fun writeKeys() {
            open().use { umn -> listOf("b:1", "a:10", "a:2").forEach { umn.create(it) } }
        }

        @Test fun `read back as CompositeKeys, in order`() {
            writeKeys()
            open().use { umn ->
                val index = umn.getIndex(idx)
                expect(keys) { index.keySequence(For.all()).toList() }
                expect(listOf("a:2", "a:10")) { index.findAllDocs(For.prefix(CompositeKey.of("a"))).values.toList() }
            }
        }

        @Test fun `are plain Object arrays, no Java serialization`() {
            writeKeys()
            // what 0.15, or any MVStore with the default ObjectDataType, reads
            MVStore.open(file).use { store ->
                val map: MVMap<Any, Any> = store.openMap("__INDEX2_composite")
                val stored = map.keyIterator(null).asSequence().toList()
                stored.forEach { expect<Class<*>>(Array<Any>::class.java) { it.javaClass } }
                assertContentEquals(keys.map { it.toList() }, stored.map { (it as Array<*>).toList() })
            }
        }
    }
}
