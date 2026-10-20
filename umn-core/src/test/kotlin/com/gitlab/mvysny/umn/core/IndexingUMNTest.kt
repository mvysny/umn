package com.gitlab.mvysny.umn.core

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.*
import kotlin.test.expect

/**
 * A testing battery which tests [IndexingUMN] backed by [newStorage]; run it like [GenericUmnTestBattery].
 */
abstract class IndexingUmnTestBattery {
    /**
     * Returns a new empty storage; every test wraps its own in an [IndexingUMN].
     */
    abstract fun newStorage(): UMN

    private val idxIdentity = IndexingUMN.IndexDef.simple<String, String>("identity", { doc -> setOf(doc) }, String::class)
    private val idxLength = IndexingUMN.IndexDef.simple<Int, String>("length", { doc -> setOf(doc.length) }, String::class)
    private val idxToLong = IndexingUMN.IndexDef.simple<Long, String>("toLong", { doc -> setOf(doc.toLong()) }, String::class)
    private val indices: Set<IndexingUMN.IndexDef<*, *, *>> = setOf(idxIdentity, idxLength)

    @Nested inner class FindFirstDoc {
        @Nested inner class ForKey {
            @Test fun `returns null on empty map`() {
                val umn = IndexingUMN(newStorage(), indices)
                expect(null) { umn.getIndex(idxIdentity).findFirstDoc(For.Key("")) }
                expect(null) { umn.getIndex(idxIdentity).findFirstDoc(For.Key("foo")) }
            }

            @Test fun `correctly finds the document`() {
                val umn = IndexingUMN(newStorage(), indices)
                val id = umn.create("kvak")
                expect(id to "kvak") { umn.getIndex(idxIdentity).findFirstDoc(For.Key("kvak")) }
            }

            @Test fun `correctly updates the indices on update()`() {
                val umn = IndexingUMN(newStorage(), indices)
                val id = umn.create("kvak")
                umn.update(id, "foo")
                expect(id to "foo") { umn.getIndex(idxIdentity).findFirstDoc(For.Key("foo")) }
                expect(null) { umn.getIndex(idxIdentity).findFirstDoc(For.Key("kvak")) }
            }

            @Test fun `correctly updates the length-indices on update()`() {
                val umn = IndexingUMN(newStorage(), indices)
                val id = umn.create("kvak")
                umn.update(id, "foo")
                expect(id to "foo") { umn.getIndex(idxLength).findFirstDoc(For.Key(3)) }
                expect(null) { umn.getIndex(idxLength).findFirstDoc(For.Key(4)) }
                // now simulate the Sync as it replays actions as they come from the log
                umn.create("kvak", id)
                umn.update(id, "foo")
                expect(id to "foo") { umn.getIndex(idxLength).findFirstDoc(For.Key(3)) }
                expect(null) { umn.getIndex(idxLength).findFirstDoc(For.Key(4)) }
            }
        }

        @Nested inner class ForKeySet {
            @Test fun `returns null on empty map`() {
                val umn = IndexingUMN(newStorage(), indices)
                expect(null) { umn.getIndex(idxIdentity).findFirstDoc(For.KeySet("", "foo", "bar", "baz")) }
            }

            @Test fun `correctly finds the document`() {
                val umn = IndexingUMN(newStorage(), indices)
                val id = umn.create("kvak")
                val id2 = umn.create("zzz")
                expect(id to "kvak") { umn.getIndex(idxIdentity).findFirstDoc(For.KeySet("", "foo", "kvak")) }
                expect(id to "kvak") { umn.getIndex(idxIdentity).findFirstDoc(For.KeySet("kvak", "foo", "")) }
            }
        }

        @Nested inner class ForAll {
            @Test fun `returns null on empty map`() {
                val umn = IndexingUMN(newStorage(), indices)
                expect(null) { umn.getIndex(idxIdentity).findFirstDoc(For.all()) }
            }

            @Test fun `correctly finds the document`() {
                val umn = IndexingUMN(newStorage(), indices)
                val id = umn.create("kvak")
                val id2 = umn.create("zzz")
                expect(id to "kvak") { umn.getIndex(idxIdentity).findFirstDoc(For.all()) }
            }
        }

        @Nested inner class ForKeyRange {
            @Test fun `returns null on empty map`() {
                val umn = IndexingUMN(newStorage(), indices)
                expect(null) { umn.getIndex(idxIdentity).findFirstDoc(For.KeyRange("a".."z")) }
            }

            @Test fun `correctly finds the document`() {
                val umn = IndexingUMN(newStorage(), indices)
                val id = umn.create("kvak")
                val id2 = umn.create("zzz")
                expect(id to "kvak") { umn.getIndex(idxIdentity).findFirstDoc(For.KeyRange("a".."zzzzz")) }
            }
        }
    }

    @Nested inner class KeySequence {
        @Nested inner class ForKey {
            @Test fun `returns null on empty map`() {
                val umn = IndexingUMN(newStorage(), indices)
                expect(listOf()) { umn.getIndex(idxIdentity).keySequence(For.Key("")).toList() }
                expect(listOf()) { umn.getIndex(idxIdentity).keySequence(For.Key("foo")).toList() }
            }

            @Test fun `correctly finds the document`() {
                val umn = IndexingUMN(newStorage(), indices)
                umn.create("kvak")
                expect(listOf("kvak")) { umn.getIndex(idxIdentity).keySequence(For.Key("kvak")).toList() }
                expect(listOf()) { umn.getIndex(idxIdentity).keySequence(For.Key("kvak2")).toList() }
            }
        }

        @Nested inner class ForKeySet {
            @Test fun `returns null on empty map`() {
                val umn = IndexingUMN(newStorage(), indices)
                expect(listOf()) { umn.getIndex(idxIdentity).keySequence(For.KeySet("", "foo", "bar", "baz")).toList() }
            }

            @Test fun `correctly finds the document`() {
                val umn = IndexingUMN(newStorage(), indices)
                umn.create("kvak")
                umn.create("zzz")
                expect(listOf("kvak")) { umn.getIndex(idxIdentity).keySequence(For.KeySet("", "foo", "kvak")).toList() }
                expect(listOf("kvak")) { umn.getIndex(idxIdentity).keySequence(For.KeySet("kvak", "foo", "")).toList() }
            }
        }

        @Nested inner class ForAll {
            @Test fun `returns null on empty map`() {
                val umn = IndexingUMN(newStorage(), indices)
                expect(listOf()) { umn.getIndex(idxIdentity).keySequence(For.all()).toList() }
            }

            @Test fun `correctly finds the document`() {
                val umn = IndexingUMN(newStorage(), indices)
                val id = umn.create("kvak")
                val id2 = umn.create("zzz")
                expect(listOf("kvak", "zzz")) { umn.getIndex(idxIdentity).keySequence(For.all()).toList() }
            }
        }

        @Nested inner class ForKeyRange {
            @Test fun `returns null on empty map`() {
                val umn = IndexingUMN(newStorage(), indices)
                expect(listOf()) { umn.getIndex(idxIdentity).keySequence(For.KeyRange("a".."z")).toList() }
            }

            @Test fun `correctly finds the document`() {
                val umn = IndexingUMN(newStorage(), indices)
                umn.create("kvak")
                umn.create("zzz")
                expect(listOf("kvak", "zzz")) { umn.getIndex(idxIdentity).keySequence(For.KeyRange("a".."zzzzz")).toList() }
                expect(listOf("kvak")) { umn.getIndex(idxIdentity).keySequence(For.KeyRange("a".."z")).toList() }
                expect(listOf("zzz")) { umn.getIndex(idxIdentity).keySequence(For.KeyRange("z".."zzzzzz")).toList() }
            }
        }
    }

    @Nested inner class FindLastDoc {
        @Nested inner class ForKey {
            @Test fun `returns null on empty map`() {
                val umn = IndexingUMN(newStorage(), indices)
                expect(null) { umn.getIndex(idxIdentity).findLastDoc(For.Key("")) }
                expect(null) { umn.getIndex(idxIdentity).findLastDoc(For.Key("foo")) }
            }

            @Test fun `correctly finds the document`() {
                val umn = IndexingUMN(newStorage(), indices)
                val id = umn.create("kvak")
                expect(id to "kvak") { umn.getIndex(idxIdentity).findLastDoc(For.Key("kvak")) }
            }

            @Test fun `correctly updates the indices on update()`() {
                val umn = IndexingUMN(newStorage(), indices)
                val id = umn.create("kvak")
                umn.update(id, "foo")
                expect(id to "foo") { umn.getIndex(idxIdentity).findLastDoc(For.Key("foo")) }
                expect(null) { umn.getIndex(idxIdentity).findLastDoc(For.Key("kvak")) }
            }

            @Test fun `correctly updates the length-indices on update()`() {
                val umn = IndexingUMN(newStorage(), indices)
                val id = umn.create("kvak")
                umn.update(id, "foo")
                expect(id to "foo") { umn.getIndex(idxLength).findLastDoc(For.Key(3)) }
                expect(null) { umn.getIndex(idxLength).findLastDoc(For.Key(4)) }
                // now simulate the Sync as it replays actions as they come from the log
                umn.create("kvak", id)
                umn.update(id, "foo")
                expect(id to "foo") { umn.getIndex(idxLength).findLastDoc(For.Key(3)) }
                expect(null) { umn.getIndex(idxLength).findLastDoc(For.Key(4)) }
            }
        }

        @Nested inner class ForKeySet {
            @Test fun `returns null on empty map`() {
                val umn = IndexingUMN(newStorage(), indices)
                expect(null) { umn.getIndex(idxIdentity).findLastDoc(For.KeySet("", "foo", "bar", "baz")) }
            }

            @Test fun `correctly finds the document`() {
                val umn = IndexingUMN(newStorage(), indices)
                val id = umn.create("kvak")
                expect(id to "kvak") { umn.getIndex(idxIdentity).findLastDoc(For.KeySet("", "foo", "kvak")) }
                expect(id to "kvak") { umn.getIndex(idxIdentity).findLastDoc(For.KeySet("kvak", "foo", "")) }
                val id2 = umn.create("zzz")
                expect(id2 to "zzz") { umn.getIndex(idxIdentity).findLastDoc(For.KeySet("", "zzz", "kvak")) }
                expect(id2 to "zzz") { umn.getIndex(idxIdentity).findLastDoc(For.KeySet("kvak", "zzz", "")) }
            }
        }

        @Nested inner class ForAll {
            @Test fun `returns null on empty map`() {
                val umn = IndexingUMN(newStorage(), indices)
                expect(null) { umn.getIndex(idxIdentity).findLastDoc(For.all()) }
            }

            @Test fun `correctly finds the document`() {
                val umn = IndexingUMN(newStorage(), indices)
                val id = umn.create("kvak")
                val id2 = umn.create("zzz")
                expect(id2 to "zzz") { umn.getIndex(idxIdentity).findLastDoc(For.all()) }
            }
        }

        @Nested inner class ForKeyRange {
            @Test fun `returns null on empty map`() {
                val umn = IndexingUMN(newStorage(), indices)
                expect(null) { umn.getIndex(idxIdentity).findLastDoc(For.KeyRange("a".."z")) }
            }

            @Test fun `correctly finds the document`() {
                val umn = IndexingUMN(newStorage(), indices)
                val id = umn.create("kvak")
                val id2 = umn.create("zzz")
                expect(id2 to "zzz") { umn.getIndex(idxIdentity).findLastDoc(For.KeyRange("a".."zzzzz")) }
            }
        }
    }

    @Nested inner class FindAllByKey {
        @Test fun `returns empty list on empty map`() {
            val umn = IndexingUMN(newStorage(), indices)
            expect(mapOf()) { umn.getIndex(idxIdentity).findAllDocs(For.Key("")) }
            expect(mapOf()) { umn.getIndex(idxIdentity).findAllDocs(For.Key("foo")) }
        }

        @Test fun `correctly finds the document`() {
            val umn = IndexingUMN(newStorage(), indices)
            val id = umn.create("kvak")
            expect(mapOf(id to "kvak")) { umn.getIndex(idxIdentity).findAllDocs(For.Key("kvak")) }
        }

        @Test fun `correctly updates the indices on update()`() {
            val umn = IndexingUMN(newStorage(), indices)
            val id = umn.create("kvak")
            umn.update(id, "foo")
            expect(mapOf(id to "foo")) { umn.getIndex(idxIdentity).findAllDocs(For.Key("foo")) }
            expect(mapOf()) { umn.getIndex(idxIdentity).findAllDocs(For.Key("kvak")) }
        }
    }

    @Nested inner class GetSize {
        @Test fun `empty UMN has 0 size`() {
            val umn = IndexingUMN(newStorage(), setOf(idxToLong))
            expect(0) { umn.getIndex(idxToLong).getSize() }
        }

        @Test fun `simple test`() {
            val umn = IndexingUMN(newStorage(), setOf(idxToLong))
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expect(1001) { umn.getIndex(idxToLong).getSize() }
        }

        @Test fun `multiple documents with the same key`() {
            val umn = IndexingUMN(newStorage(), setOf(idxLength))
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expect(1001) { umn.getIndex(idxLength).getSize() }
        }
    }

    @Nested inner class GetCountByKeyRange {
        @Test fun `empty UMN has 0 size`() {
            val umn = IndexingUMN(newStorage(), setOf(idxToLong))
            expect(0) { umn.getIndex(idxToLong).getCount(For.KeyRange(0L..1000L)) }
        }

        @Test fun `simple test`() {
            val umn = IndexingUMN(newStorage(), setOf(idxToLong))
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expect(6) { umn.getIndex(idxToLong).getCount(For.KeyRange(50L..55L)) }
            expect(100) { umn.getIndex(idxToLong).getCount(For.KeyRange(0L..99L)) }
        }

        @Test fun `multiple documents with the same key`() {
            val umn = IndexingUMN(newStorage(), setOf(idxLength))
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expect(0) { umn.getIndex(idxLength).getCount(For.KeyRange(0..0)) }
            expect(10) { umn.getIndex(idxLength).getCount(For.KeyRange(1..1)) }
            expect(100) { umn.getIndex(idxLength).getCount(For.KeyRange(1..2)) }
            expect(1000) { umn.getIndex(idxLength).getCount(For.KeyRange(1..3)) }
            expect(90) { umn.getIndex(idxLength).getCount(For.KeyRange(2..2)) }
            expect(990) { umn.getIndex(idxLength).getCount(For.KeyRange(2..3)) }
            expect(900) { umn.getIndex(idxLength).getCount(For.KeyRange(3..3)) }
        }
    }

    @Nested inner class FindAllDocsPaged {
        @Test fun `check that the result documents are returned in a correct order`() {
            val umn = IndexingUMN(newStorage(), setOf(idxToLong))
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expect(listOf("50", "51", "52", "53", "54", "55")) { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(50L..55L)).values.toList() }
            expect(listOf("1000")) { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(1000L..2000L)).values.toList() }
            expect(listOf()) { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(1001L..2000L)).values.toList() }
            expect(listOf()) { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(-1000L..-10L)).values.toList() }
            expect(listOf()) { umn.getIndex(idxToLong).findAllDocsPaged(For.KeySet()).values.toList() }
            expect(listOf()) { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(1000L..2000L), 5..4).values.toList() }
        }

        @Test fun `check offset`() {
            val umn = IndexingUMN(newStorage(), setOf(idxToLong))
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expect(listOf("52", "53")) { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(50L..55L), 2..3).values.toList() }
            expect(listOf("50", "51")) { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(50L..55L), 0..1).values.toList() }
        }

        @Test fun `multiple values mapping to a Long key`() {
            val umn = IndexingUMN(newStorage(), setOf(IndexingUMN.IndexDef.simple("toLong", { doc -> setOf(doc.toLong() / 3) }, String::class)))
            (0..1000).forEach { umn.create(it.toString()) }
            expect(listOf("48", "49", "50", "51", "52", "53", "54", "55", "56")) { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(16L..18L)).values.sorted() }
            expect(listOf()) { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(1000L..2000L)).values.toList() }
            expect(listOf()) { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(-1000L..-10L)).values.toList() }
        }

        @Test fun `using CKey`() {
            val idx = IndexingUMN.IndexDef.simple("index", { doc -> setOf(doc) }, CKey::class)
            val umn = IndexingUMN(newStorage(), setOf(idx))

            (0..1000).forEach { umn.create(CKey("foo", it.toFloat())) }
            (0..1000).forEach { umn.create(CKey("bar", it.toFloat())) }
            (0..1000).forEach { umn.create(CKey("baz", it.toFloat())) }
            expect(listOf()) { umn.getIndex(idx).findAllDocsPaged(For.KeyRange(CKey("kvak", 0f)..CKey("kvak", 10f))).values.sorted() }
            expect(listOf()) { umn.getIndex(idx).findAllDocsPaged(For.KeyRange(CKey("bar", 2000f)..CKey("bar", 3000f))).values.sorted() }
            expect((0..10).map { it.toFloat() }) {
                umn.getIndex(idx).findAllDocsPaged(For.KeyRange(CKey("foo", 0f)..CKey("foo", 10f))).values.map { it.ordering }
            }
        }
    }

    @Nested inner class FindAllIDsPaged {
        @Test fun `check that the result documents are returned in a correct order`() {
            val umn = IndexingUMN(newStorage(), setOf(idxToLong))
            val ids: List<UUID> = (0..1000).map { umn.create(it.toString()) }
            expect(ids.subList(50, 56)) { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(50L..55L)).toList() }
            expect(listOf(ids.last())) { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(1000L..2000L)).toList() }
            expect(listOf()) { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(1001L..2000L)).toList() }
            expect(listOf()) { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(-1000L..-10L)).toList() }
            expect(listOf()) { umn.getIndex(idxToLong).findAllIDsPaged(For.KeySet()).toList() }
            expect(listOf()) { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(1000L..2000L), 5..4).toList() }
        }

        @Test fun `check offset`() {
            val umn = IndexingUMN(newStorage(), setOf(idxToLong))
            val ids: List<UUID> = (0..1000).map { umn.create(it.toString()) }
            expect(ids.subList(52, 54)) { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(50L..55L), 2..3).toList() }
            expect(ids.subList(50, 52)) { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(50L..55L), 0..1).toList() }
        }

        @Test fun `multiple values mapping to a Long key`() {
            val umn = IndexingUMN(newStorage(), setOf(IndexingUMN.IndexDef.simple("toLong", { doc -> setOf(doc.toLong() / 3) }, String::class)))
            val ids: List<UUID> = (0..1000).map { umn.create(it.toString()) }
            expect(ids.subList(48, 57).toSet()) { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(16L..18L)).toSet() }
            expect(listOf()) { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(1000L..2000L)).toList() }
            expect(listOf()) { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(-1000L..-10L)).toList() }
        }

        @Test fun `using CKey`() {
            val idx = IndexingUMN.IndexDef.simple("index", { doc -> setOf(doc) }, CKey::class)
            val umn = IndexingUMN(newStorage(), setOf(idx))

            (0..1000).forEach { umn.create(CKey("foo", it.toFloat())) }
            (0..1000).forEach { umn.create(CKey("bar", it.toFloat())) }
            (0..1000).forEach { umn.create(CKey("baz", it.toFloat())) }
            expect(listOf()) { umn.getIndex(idx).findAllIDsPaged(For.KeyRange(CKey("kvak", 0f)..CKey("kvak", 10f))).toList() }
            expect(listOf()) { umn.getIndex(idx).findAllIDsPaged(For.KeyRange(CKey("bar", 2000f)..CKey("bar", 3000f))).toList() }
        }
    }

    @Nested inner class AllDocumentIdSequence {
        @Test fun `initially the iterator is empty`() {
            val umn = IndexingUMN(newStorage(), indices)
            expect(listOf()) { umn.getIndex(idxIdentity).getSequenceOfIDs(For.all()).toList() }
            expect(listOf()) { umn.getIndex(idxLength).getSequenceOfIDs(For.all()).toList() }
        }
        @Test fun `iterator lists all documents`() {
            val umn = IndexingUMN(newStorage(), indices)
            val ids: Set<UUID> = (0..100).map { umn.create("$it") } .toSet()
            expect(ids) { umn.getIndex(idxIdentity).getSequenceOfIDs(For.all()).toSet() }
            expect(ids) { umn.getIndex(idxLength).getSequenceOfIDs(For.all()).toSet() }
        }
        @Test fun `iterator doesn't list removed documents`() {
            val umn = IndexingUMN(newStorage(), indices)
            val ids: Set<UUID> = (0..10).map { umn.create("$it") } .toSet()
            ids.forEach { umn.delete(it) }
            expect(null) { umn.getIndex(idxIdentity).findFirstID(For.Key("0")) }
            expect(listOf()) { umn.getIndex(idxIdentity).getSequenceOfIDs(For.all()).toList() }
            expect(null) { umn.getIndex(idxLength).findFirstID(For.Key(1)) }
            expect(listOf()) { umn.getIndex(idxLength).getSequenceOfIDs(For.all()).toList() }
        }
        @Test fun `iterating IDs doesn't fail even when the documents are being deleted`() {
            val umn = IndexingUMN(newStorage(), indices)
            val ids = (0..9).map { umn.create("$it") } .toSet()
            val collected = umn.getIndex(idxIdentity).getSequenceOfIDs(For.all()).onEach {
                ids.forEach { id -> umn.delete(id) }
            } .toList()
            expect(ids.toList()) { collected }
            expect(null) { umn.getIndex(idxIdentity).findFirstID(For.Key("0")) }
            expect(listOf()) { umn.getIndex(idxIdentity).getSequenceOfIDs(For.all()).toList() }
            expect(null) { umn.getIndex(idxLength).findFirstID(For.Key(1)) }
            expect(listOf()) { umn.getIndex(idxLength).getSequenceOfIDs(For.all()).toList() }
        }
    }

    @Nested inner class DocumentIdSequenceByKeyRange {
        @Test fun `initially the iterator is empty`() {
            val umn = IndexingUMN(newStorage(), indices)
            expect(listOf()) { umn.getIndex(idxIdentity).getSequenceOfIDs(For.KeyRange("0".."20")).toList() }
            expect(listOf()) { umn.getIndex(idxLength).getSequenceOfIDs(For.KeyRange(0..3)).toList() }
        }
        @Test fun `iterator lists all documents`() {
            val umn = IndexingUMN(newStorage(), indices)
            val ids: Set<UUID> = (0..11).map { umn.create("$it") } .toSet()
            expect(ids) { umn.getIndex(idxIdentity).getSequenceOfIDs(For.KeyRange("".."9999")).toSet() }
            expect(ids) { umn.getIndex(idxLength).getSequenceOfIDs(For.KeyRange(0..10)).toSet() }
        }
        @Test fun `iterator lists only matching documents`() {
            val umn = IndexingUMN(newStorage(), indices)
            val ids: Set<UUID> = (0..10).map { umn.create("$it") } .toSet()
            expect(ids.toList().subList(0, 10)) { umn.getIndex(idxLength).getSequenceOfIDs(For.KeyRange(0..1)).toList() }
            expect(ids.toList().subList(10, 11)) { umn.getIndex(idxLength).getSequenceOfIDs(For.KeyRange(2..2)).toList() }
        }
        @Test fun `iterator doesn't list removed documents`() {
            val umn = IndexingUMN(newStorage(), indices)
            val ids: Set<UUID> = (0..10).map { umn.create("$it") } .toSet()
            ids.forEach { umn.delete(it) }
            expect(null) { umn.getIndex(idxIdentity).findFirstID(For.Key("0")) }
            expect(listOf()) { umn.getIndex(idxIdentity).getSequenceOfIDs(For.KeyRange("0".."1000")).toList() }
            expect(null) { umn.getIndex(idxLength).findFirstID(For.Key(1)) }
            expect(listOf()) { umn.getIndex(idxLength).getSequenceOfIDs(For.KeyRange(0..10)).toList() }
        }
        @Test fun `iterating IDs doesn't fail even when the documents are being deleted`() {
            val umn = IndexingUMN(newStorage(), indices)
            val ids = (0..9).map { umn.create("$it") } .toSet()
            val collected = umn.getIndex(idxIdentity).getSequenceOfIDs(For.all()).onEach {
                ids.forEach { id -> umn.delete(id) }
            } .toList()
            expect(ids.toList()) { collected }
            expect(null) { umn.getIndex(idxIdentity).findFirstID(For.Key("0")) }
            expect(listOf()) { umn.getIndex(idxIdentity).getSequenceOfIDs(For.KeyRange("0".."1000")).toList() }
            expect(null) { umn.getIndex(idxLength).findFirstID(For.Key(1)) }
            expect(listOf()) { umn.getIndex(idxLength).getSequenceOfIDs(For.KeyRange(0..10)).toList() }
        }
    }

    @Test fun `Indices should ignore incompatible documents`() {
        // all indices expect Strings. Let's feed them Long, they should ignore it.
        val umn = IndexingUMN(newStorage(), indices)
        val id = umn.create(25L)
        umn.update(id, 45L as java.lang.Long)
        expect(45L as java.lang.Long) { umn.get(id, java.lang.Long::class) }
        umn.delete(id)
        expect(null) { umn.find(id, java.lang.Long::class) }
    }

    @Nested inner class IsEmpty {
        @Test fun `initially the index is empty`() {
            val umn = IndexingUMN(newStorage(), indices)
            expect(true) { umn.getIndex(idxIdentity).isEmpty() }
            expect(true) { umn.getIndex(idxLength).isEmpty() }
        }
        @Test fun `not empty when there are indexed documents`() {
            val umn = IndexingUMN(newStorage(), indices)
            (0..100).forEach { umn.create("$it") }
            expect(false) { umn.getIndex(idxIdentity).isEmpty() }
            expect(false) { umn.getIndex(idxLength).isEmpty() }
        }
        @Test fun `empty when all documents has been deleted`() {
            val umn = IndexingUMN(newStorage(), indices)
            val ids: Set<UUID> = (0..10).map { umn.create("$it") } .toSet()
            ids.forEach { umn.delete(it) }
            expect(true) { umn.getIndex(idxIdentity).isEmpty() }
            expect(true) { umn.getIndex(idxLength).isEmpty() }
        }
    }

    @Nested inner class ComplexIndex {
        @Test fun `fast parent retrieval`() {
            // in this example we're going to store the parent IDs instead of IDs of the children, to allow fast parent retrieval.
            // Key will be the name of the child.
            data class Parent(val name: String) : Serializable
            data class Child(val name: String, val parentId: UUID) : Serializable

            val idxParent = IndexingUMN.IndexDef<String, Child, Parent>("quick_parent_lookup",
                { doc -> setOf(doc.name) }, Child::class, Parent::class, { _, doc -> doc.parentId })
            val umn = IndexingUMN(newStorage(), setOf(idxParent))
            val parentIDs = listOf("a", "b", "c").map { umn.create(Parent(it)) }
            val childrenOfA = listOf("a1", "a2", "a3").map { umn.create(Child(it, parentIDs[0])) }
            listOf("b1").forEach { umn.create(Child(it, parentIDs[1])) }
            listOf("c1", "c2").forEach { umn.create(Child(it, parentIDs[2])) }
            val idx = umn.getIndex(idxParent)

            // now the index only indexes Children so Parent lookup should return null
            expect(null) { idx.findFirstID(For.Key("a")) }
            expect(0) { idx.findAllIDs(For.Key("a")).size }

            // lookup of "a1" should yield the parent "a"
            expect("a") { idx.findFirstDoc(For.Key("a1"))!!.second.name }
            expect("a") { idx.findFirstDoc(For.Key("a2"))!!.second.name }
            expect("a") { idx.findFirstDoc(For.Key("a3"))!!.second.name }
            expect("b") { idx.findFirstDoc(For.Key("b1"))!!.second.name }
            expect("c") { idx.findFirstDoc(For.Key("c2"))!!.second.name }
            expect("c") { idx.findFirstDoc(For.Key("c1"))!!.second.name }

            // now update a1 to be c3
            umn.update(childrenOfA[0], Child("c3", parentIDs[2]))
            expect("c") { idx.findFirstDoc(For.Key("c3"))!!.second.name }
            expect(null) { idx.findFirstDoc(For.Key("a1")) }

            // now rename a2 to a4, but keep the same parent
            umn.update(childrenOfA[1], Child("a4", parentIDs[0]))
            expect("a") { idx.findFirstDoc(For.Key("a4"))!!.second.name }
            expect(null) { idx.findFirstDoc(For.Key("a2")) }
        }

        @Test fun `Indices should ignore incompatible documents`() {
            // in this example we're going to store the parent IDs instead of IDs of the children, to allow fast parent retrieval.
            // Key will be the name of the child.
            data class Parent(val name: String) : Serializable
            data class Child(val name: String, val parentId: UUID) : Serializable

            val idxParent = IndexingUMN.IndexDef<String, Child, Parent>("quick_parent_lookup",
                { doc -> setOf(doc.name) }, Child::class, Parent::class, { _, doc -> doc.parentId })

            // all indices expect Child. Let's feed them Long, they should ignore it.
            val umn = IndexingUMN(newStorage(), indices)
            val id = umn.create(25L)
            umn.update(id, 45L as java.lang.Long)
            expect(45L as java.lang.Long) { umn.get(id, java.lang.Long::class) }
            umn.delete(id)
            expect(null) { umn.find(id, java.lang.Long::class) }
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
class IndexingUMNTest {
    // doesn't test the indexing capabilities per se; it tests whether the IndexingUMN still behaves as a proper UMN storage.
    @Nested inner class BasicTests {
        @Nested inner class InMemory : GenericUmnTestBattery() {
            override fun newUmn(): UMN {
                val indices = setOf(IndexingUMN.IndexDef.simple("identityHashCode", { doc -> setOf(System.identityHashCode(doc)) }, Any::class))
                return IndexingUMN(InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer), indices)
            }
        }
        @Nested inner class OnMvstore : GenericUmnTestBattery() {
            override fun newUmn(): UMN {
                val indices = setOf(IndexingUMN.IndexDef.simple("identityHashCode", { doc -> setOf(System.identityHashCode(doc)) }, Any::class))
                val mvstore = createMvstoreUMN()
                return IndexingUMN(mvstore, indices)
            }
        }
    }

    // tests the indexing capabilities of the IndexingUMN
    @Nested inner class IndexTests {
        @Nested inner class InMemory : IndexingUmnTestBattery() {
            override fun newStorage(): UMN = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer)
        }
        @Nested inner class OnMvstore : IndexingUmnTestBattery() {
            override fun newStorage(): UMN = createMvstoreUMN()
        }
    }
}
