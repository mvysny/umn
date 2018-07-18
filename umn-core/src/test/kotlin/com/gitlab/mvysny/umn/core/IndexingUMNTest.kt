package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectList
import org.h2.mvstore.MVStore
import java.io.Serializable
import java.util.*
import kotlin.test.expect

/**
 * A testing battery which tests [IndexingUMN] backed by [storageProvider].
 */
fun DynaNodeGroup.indexingUmnTestBattery(storageProvider: ()->UMN) {
    lateinit var storage: UMN
    beforeEach { storage = storageProvider() }
    afterEach { storage.close() }

    val idxIdentity = IndexingUMN.IndexDef<String, String>("identity", { doc -> setOf(doc) }, String::class)
    val idxLength = IndexingUMN.IndexDef<Int, String>("length", { doc -> setOf(doc.length) }, String::class)
    val idxToLong = IndexingUMN.IndexDef<Long, String>("toLong", { doc -> setOf(doc.toLong()) }, String::class)
    val indices: Set<IndexingUMN.IndexDef<*, *>> = setOf(idxIdentity, idxLength)

    group("findOneByKey()") {
        test("returns null on empty map") {
            val umn = IndexingUMN(storageProvider(), indices)
            expect(null) { umn.getIndex(idxIdentity).findOneByKey("") }
            expect(null) { umn.getIndex(idxIdentity).findOneByKey("foo") }
        }

        test("correctly finds the document") {
            val umn = IndexingUMN(storageProvider(), indices)
            val id = umn.create("kvak")
            expect(id to "kvak") { umn.getIndex(idxIdentity).findOneByKey("kvak") }
        }

        test("correctly updates the indices on update()") {
            val umn = IndexingUMN(storageProvider(), indices)
            val id = umn.create("kvak")
            umn.update(id, "foo", String::class)
            expect(id to "foo") { umn.getIndex(idxIdentity).findOneByKey("foo") }
            expect(null) { umn.getIndex(idxIdentity).findOneByKey("kvak") }
        }

        test("correctly updates the length-indices on update()") {
            val umn = IndexingUMN(storageProvider(), indices)
            val id = umn.create("kvak")
            umn.update(id, "foo", String::class)
            expect(id to "foo") { umn.getIndex(idxLength).findOneByKey(3) }
            expect(null) { umn.getIndex(idxLength).findOneByKey(4) }
            // now simulate the Sync as it replays actions as they come from the log
            umn.create("kvak", id)
            umn.update(id, "foo", String::class)
            expect(id to "foo") { umn.getIndex(idxLength).findOneByKey(3) }
            expect(null) { umn.getIndex(idxLength).findOneByKey(4) }
        }
    }

    group("findOneByKeys()") {
        test("returns null on empty map") {
            val umn = IndexingUMN(storageProvider(), indices)
            expect(null) { umn.getIndex(idxIdentity).findOneByKeys(setOf("", "foo", "bar", "baz")) }
        }

        test("correctly finds the document") {
            val umn = IndexingUMN(storageProvider(), indices)
            val id = umn.create("kvak")
            expect(id to "kvak") { umn.getIndex(idxIdentity).findOneByKeys(setOf("", "foo", "kvak")) }
            expect(id to "kvak") { umn.getIndex(idxIdentity).findOneByKeys(setOf("kvak", "foo", "")) }
        }
    }

    group("findAllByKey()") {
        test("returns empty list on empty map") {
            val umn = IndexingUMN(storageProvider(), indices)
            expect(mapOf()) { umn.getIndex(idxIdentity).findAllByKey("") }
            expect(mapOf()) { umn.getIndex(idxIdentity).findAllByKey("foo") }
        }

        test("correctly finds the document") {
            val umn = IndexingUMN(storageProvider(), indices)
            val id = umn.create("kvak")
            expect(mapOf(id to "kvak")) { umn.getIndex(idxIdentity).findAllByKey("kvak") }
        }

        test("correctly updates the indices on update()") {
            val umn = IndexingUMN(storageProvider(), indices)
            val id = umn.create("kvak")
            umn.update(id, "foo", String::class)
            expect(mapOf(id to "foo")) { umn.getIndex(idxIdentity).findAllByKey("foo") }
            expect(mapOf()) { umn.getIndex(idxIdentity).findAllByKey("kvak") }
        }
    }

    group("getSize()") {
        test("empty UMN has 0 size") {
            val umn = IndexingUMN(storageProvider(), setOf(idxToLong))
            expect(0) { umn.getIndex(idxToLong).getSize() }
        }

        test("simple test") {
            val umn = IndexingUMN(storageProvider(), setOf(idxToLong))
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expect(1001) { umn.getIndex(idxToLong).getSize() }
        }

        test("multiple documents with the same key") {
            val umn = IndexingUMN(storageProvider(), setOf(idxLength))
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expect(1001) { umn.getIndex(idxLength).getSize() }
        }
    }

    group("getCountByKeyRange()") {
        test("empty UMN has 0 size") {
            val umn = IndexingUMN(storageProvider(), setOf(idxToLong))
            expect(0) { umn.getIndex(idxToLong).getCountByKeyRange(0L..1000L) }
        }

        test("simple test") {
            val umn = IndexingUMN(storageProvider(), setOf(idxToLong))
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expect(6) { umn.getIndex(idxToLong).getCountByKeyRange(50L..55L) }
            expect(100) { umn.getIndex(idxToLong).getCountByKeyRange(0L..99L) }
        }

        test("multiple documents with the same key") {
            val umn = IndexingUMN(storageProvider(), setOf(idxLength))
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expect(0) { umn.getIndex(idxLength).getCountByKeyRange(0..0) }
            expect(10) { umn.getIndex(idxLength).getCountByKeyRange(1..1) }
            expect(100) { umn.getIndex(idxLength).getCountByKeyRange(1..2) }
            expect(1000) { umn.getIndex(idxLength).getCountByKeyRange(1..3) }
            expect(90) { umn.getIndex(idxLength).getCountByKeyRange(2..2) }
            expect(990) { umn.getIndex(idxLength).getCountByKeyRange(2..3) }
            expect(900) { umn.getIndex(idxLength).getCountByKeyRange(3..3) }
        }
    }

    group("findAllByKeyRange()") {
        test("check that the result documents are returned in a correct order") {
            val umn = IndexingUMN(storageProvider(), setOf(idxToLong))
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expectList("50", "51", "52", "53", "54", "55") { umn.getIndex(idxToLong).findAllByKeyRange(50L..55L, String::class).values.toList() }
            expectList("1000") { umn.getIndex(idxToLong).findAllByKeyRange(1000L..2000L, String::class).values.toList() }
            expectList() { umn.getIndex(idxToLong).findAllByKeyRange(1001L..2000L, String::class).values.toList() }
            expectList() { umn.getIndex(idxToLong).findAllByKeyRange(-1000L..-10L, String::class).values.toList() }
        }

        test("check offset") {
            val umn = IndexingUMN(storageProvider(), setOf(idxToLong))
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expectList("52", "53") { umn.getIndex(idxToLong).findAllByKeyRange(50L..55L, String::class, 2..3).values.toList() }
        }

        test("multiple values mapping to a Long key") {
            val umn = IndexingUMN(storageProvider(), setOf(IndexingUMN.IndexDef("toLong", { doc -> setOf(doc.toLong() / 3) }, String::class)))
            (0..1000).forEach { umn.create(it.toString()) }
            expectList("48", "49", "50", "51", "52", "53", "54", "55", "56") { umn.getIndex(idxToLong).findAllByKeyRange(16L..18L, String::class).values.sorted() }
            expectList() { umn.getIndex(idxToLong).findAllByKeyRange(1000L..2000L, String::class).values.toList() }
            expectList() { umn.getIndex(idxToLong).findAllByKeyRange(-1000L..-10L, String::class).values.toList() }
        }

        test("using CKey") {
            val idx = IndexingUMN.IndexDef("index", { doc -> setOf(doc) }, CKey::class)
            val umn = IndexingUMN(storageProvider(), setOf(idx))

            (0..1000).forEach { umn.create(CKey("foo", it.toFloat())) }
            (0..1000).forEach { umn.create(CKey("bar", it.toFloat())) }
            (0..1000).forEach { umn.create(CKey("baz", it.toFloat())) }
            expectList() { umn.getIndex(idx).findAllByKeyRange(CKey("kvak", 0f)..CKey("kvak", 10f), CKey::class).values.sorted() }
            expectList() { umn.getIndex(idx).findAllByKeyRange(CKey("bar", 2000f)..CKey("bar", 3000f), CKey::class).values.sorted() }
            expect((0..10).map { it.toFloat() }) {
                umn.getIndex(idx).findAllByKeyRange(CKey("foo", 0f)..CKey("foo", 10f), CKey::class).values.map { it.ordering }
            }
        }
    }

    group("documentIdIterator()") {
        test("initially the iterator is empty") {
            val umn = IndexingUMN(storageProvider(), indices)
            expectList() { umn.getIndex(idxIdentity).allDocumentIdSequence().toList() }
            expectList() { umn.getIndex(idxLength).allDocumentIdSequence().toList() }
        }
        test("iterator lists all documents") {
            val umn = IndexingUMN(storageProvider(), indices)
            val ids: Set<UUID> = (0..100).map { umn.create("$it") } .toSet()
            expect(ids) { umn.getIndex(idxIdentity).allDocumentIdSequence().toSet() }
            expect(ids) { umn.getIndex(idxLength).allDocumentIdSequence().toSet() }
        }
        test("iterator doesn't list removed documents") {
            val umn = IndexingUMN(storageProvider(), indices)
            val ids: Set<UUID> = (0..10).map { umn.create("$it") } .toSet()
            ids.forEach { umn.delete(it, String::class) }
            expect(null) { umn.getIndex(idxIdentity).findOneByKey("0") }
            expectList() { umn.getIndex(idxIdentity).allDocumentIdSequence().toList() }
            expect(null) { umn.getIndex(idxLength).findOneByKey(1) }
            expectList() { umn.getIndex(idxLength).allDocumentIdSequence().toList() }
        }
        test("iterating IDs doesn't fail even when the documents are being deleted") {
            val umn = IndexingUMN(storageProvider(), indices)
            (0..10).map { umn.create("$it") } .toSet()
            umn.getIndex(idxIdentity).allDocumentIdSequence().forEach { umn.delete(it, String::class) }
            expect(null) { umn.getIndex(idxIdentity).findOneByKey("0") }
            expectList() { umn.getIndex(idxIdentity).allDocumentIdSequence().toList() }
            expect(null) { umn.getIndex(idxLength).findOneByKey(1) }
            expectList() { umn.getIndex(idxLength).allDocumentIdSequence().toList() }
        }
    }

    group("isEmpty()") {
        test("initially the index is empty") {
            val umn = IndexingUMN(storageProvider(), indices)
            expect(true) { umn.getIndex(idxIdentity).isEmpty() }
            expect(true) { umn.getIndex(idxLength).isEmpty() }
        }
        test("not empty when there are indexed documents") {
            val umn = IndexingUMN(storageProvider(), indices)
            val ids: Set<UUID> = (0..100).map { umn.create("$it") } .toSet()
            expect(false) { umn.getIndex(idxIdentity).isEmpty() }
            expect(false) { umn.getIndex(idxLength).isEmpty() }
        }
        test("empty when all documents has been deleted") {
            val umn = IndexingUMN(storageProvider(), indices)
            val ids: Set<UUID> = (0..10).map { umn.create("$it") } .toSet()
            ids.forEach { umn.delete(it, String::class) }
            expect(true) { umn.getIndex(idxIdentity).isEmpty() }
            expect(true) { umn.getIndex(idxLength).isEmpty() }
        }
    }
}

/**
 * Composite key.
 */
data class CKey(val category: String, val ordering: Float) : Serializable, Comparable<CKey> {
    override fun compareTo(other: CKey): Int = compareValuesBy(this, other, { it.category }, { it.ordering })
}

/**
 * Tests the [IndexingUMN] class.
 */
class IndexingUMNTest : DynaTest({
    // doesn't test the indexing capabilities per se; it tests whether the IndexingUMN still behaves as a proper UMN storage.
    group("basic tests") {
        group("in-memory") {
            genericUmnTestBattery {
                val indices = setOf(IndexingUMN.IndexDef("identityHashCode", { doc -> setOf(System.identityHashCode(doc)) }, Any::class))
                IndexingUMN(InMemoryUMN(), indices)
            }
        }
        group("on mvstore") {
            genericUmnTestBattery {
                val indices = setOf(IndexingUMN.IndexDef("identityHashCode", { doc -> setOf(System.identityHashCode(doc)) }, Any::class))
                val mvstore = MvstoreUMN(MVStore.open(createTempFile().absolutePath), JavaDocumentSerializer)
                IndexingUMN(mvstore, indices)
            }
        }
    }

    // tests the indexing capabilities of the IndexingUMN
    group("index tests") {
        group("in-memory") {
            indexingUmnTestBattery { InMemoryUMN() }
        }
        group("on mvstore") {
            indexingUmnTestBattery { createMvstoreUMN() }
        }
    }
})
