package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectList
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

    val idxIdentity = IndexingUMN.IndexDef.simple<String, String>("identity", { doc -> setOf(doc) }, String::class)
    val idxLength = IndexingUMN.IndexDef.simple<Int, String>("length", { doc -> setOf(doc.length) }, String::class)
    val idxToLong = IndexingUMN.IndexDef.simple<Long, String>("toLong", { doc -> setOf(doc.toLong()) }, String::class)
    val indices: Set<IndexingUMN.IndexDef<*, *, *>> = setOf(idxIdentity, idxLength)

    group("findFirstDoc()") {
        group("For.Key") {
            test("returns null on empty map") {
                val umn = IndexingUMN(storageProvider(), indices)
                expect(null) { umn.getIndex(idxIdentity).findFirstDoc(For.Key("")) }
                expect(null) { umn.getIndex(idxIdentity).findFirstDoc(For.Key("foo")) }
            }

            test("correctly finds the document") {
                val umn = IndexingUMN(storageProvider(), indices)
                val id = umn.create("kvak")
                expect(id to "kvak") { umn.getIndex(idxIdentity).findFirstDoc(For.Key("kvak")) }
            }

            test("correctly updates the indices on update()") {
                val umn = IndexingUMN(storageProvider(), indices)
                val id = umn.create("kvak")
                umn.update(id, "foo")
                expect(id to "foo") { umn.getIndex(idxIdentity).findFirstDoc(For.Key("foo")) }
                expect(null) { umn.getIndex(idxIdentity).findFirstDoc(For.Key("kvak")) }
            }

            test("correctly updates the length-indices on update()") {
                val umn = IndexingUMN(storageProvider(), indices)
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

        group("For.KeySet()") {
            test("returns null on empty map") {
                val umn = IndexingUMN(storageProvider(), indices)
                expect(null) { umn.getIndex(idxIdentity).findFirstDoc(For.KeySet("", "foo", "bar", "baz")) }
            }

            test("correctly finds the document") {
                val umn = IndexingUMN(storageProvider(), indices)
                val id = umn.create("kvak")
                val id2 = umn.create("zzz")
                expect(id to "kvak") { umn.getIndex(idxIdentity).findFirstDoc(For.KeySet("", "foo", "kvak")) }
                expect(id to "kvak") { umn.getIndex(idxIdentity).findFirstDoc(For.KeySet("kvak", "foo", "")) }
            }
        }

        group("For.all()") {
            test("returns null on empty map") {
                val umn = IndexingUMN(storageProvider(), indices)
                expect(null) { umn.getIndex(idxIdentity).findFirstDoc(For.all()) }
            }

            test("correctly finds the document") {
                val umn = IndexingUMN(storageProvider(), indices)
                val id = umn.create("kvak")
                val id2 = umn.create("zzz")
                expect(id to "kvak") { umn.getIndex(idxIdentity).findFirstDoc(For.all()) }
            }
        }

        group("For.KeyRange()") {
            test("returns null on empty map") {
                val umn = IndexingUMN(storageProvider(), indices)
                expect(null) { umn.getIndex(idxIdentity).findFirstDoc(For.KeyRange("a".."z")) }
            }

            test("correctly finds the document") {
                val umn = IndexingUMN(storageProvider(), indices)
                val id = umn.create("kvak")
                val id2 = umn.create("zzz")
                expect(id to "kvak") { umn.getIndex(idxIdentity).findFirstDoc(For.KeyRange("a".."zzzzz")) }
            }
        }
    }

    group("findLastDoc()") {
        group("For.Key") {
            test("returns null on empty map") {
                val umn = IndexingUMN(storageProvider(), indices)
                expect(null) { umn.getIndex(idxIdentity).findLastDoc(For.Key("")) }
                expect(null) { umn.getIndex(idxIdentity).findLastDoc(For.Key("foo")) }
            }

            test("correctly finds the document") {
                val umn = IndexingUMN(storageProvider(), indices)
                val id = umn.create("kvak")
                expect(id to "kvak") { umn.getIndex(idxIdentity).findLastDoc(For.Key("kvak")) }
            }

            test("correctly updates the indices on update()") {
                val umn = IndexingUMN(storageProvider(), indices)
                val id = umn.create("kvak")
                umn.update(id, "foo")
                expect(id to "foo") { umn.getIndex(idxIdentity).findLastDoc(For.Key("foo")) }
                expect(null) { umn.getIndex(idxIdentity).findLastDoc(For.Key("kvak")) }
            }

            test("correctly updates the length-indices on update()") {
                val umn = IndexingUMN(storageProvider(), indices)
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

        group("For.KeySet()") {
            test("returns null on empty map") {
                val umn = IndexingUMN(storageProvider(), indices)
                expect(null) { umn.getIndex(idxIdentity).findLastDoc(For.KeySet("", "foo", "bar", "baz")) }
            }

            test("correctly finds the document") {
                val umn = IndexingUMN(storageProvider(), indices)
                val id = umn.create("kvak")
                expect(id to "kvak") { umn.getIndex(idxIdentity).findLastDoc(For.KeySet("", "foo", "kvak")) }
                expect(id to "kvak") { umn.getIndex(idxIdentity).findLastDoc(For.KeySet("kvak", "foo", "")) }
                val id2 = umn.create("zzz")
                expect(id2 to "zzz") { umn.getIndex(idxIdentity).findLastDoc(For.KeySet("", "zzz", "kvak")) }
                expect(id2 to "zzz") { umn.getIndex(idxIdentity).findLastDoc(For.KeySet("kvak", "zzz", "")) }
            }
        }

        group("For.all()") {
            test("returns null on empty map") {
                val umn = IndexingUMN(storageProvider(), indices)
                expect(null) { umn.getIndex(idxIdentity).findLastDoc(For.all()) }
            }

            test("correctly finds the document") {
                val umn = IndexingUMN(storageProvider(), indices)
                val id = umn.create("kvak")
                val id2 = umn.create("zzz")
                expect(id2 to "zzz") { umn.getIndex(idxIdentity).findLastDoc(For.all()) }
            }
        }

        group("For.KeyRange()") {
            test("returns null on empty map") {
                val umn = IndexingUMN(storageProvider(), indices)
                expect(null) { umn.getIndex(idxIdentity).findLastDoc(For.KeyRange("a".."z")) }
            }

            test("correctly finds the document") {
                val umn = IndexingUMN(storageProvider(), indices)
                val id = umn.create("kvak")
                val id2 = umn.create("zzz")
                expect(id2 to "zzz") { umn.getIndex(idxIdentity).findLastDoc(For.KeyRange("a".."zzzzz")) }
            }
        }
    }

    group("findAllByKey()") {
        test("returns empty list on empty map") {
            val umn = IndexingUMN(storageProvider(), indices)
            expect(mapOf()) { umn.getIndex(idxIdentity).findAllDocs(For.Key("")) }
            expect(mapOf()) { umn.getIndex(idxIdentity).findAllDocs(For.Key("foo")) }
        }

        test("correctly finds the document") {
            val umn = IndexingUMN(storageProvider(), indices)
            val id = umn.create("kvak")
            expect(mapOf(id to "kvak")) { umn.getIndex(idxIdentity).findAllDocs(For.Key("kvak")) }
        }

        test("correctly updates the indices on update()") {
            val umn = IndexingUMN(storageProvider(), indices)
            val id = umn.create("kvak")
            umn.update(id, "foo")
            expect(mapOf(id to "foo")) { umn.getIndex(idxIdentity).findAllDocs(For.Key("foo")) }
            expect(mapOf()) { umn.getIndex(idxIdentity).findAllDocs(For.Key("kvak")) }
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
            expect(0) { umn.getIndex(idxToLong).getCount(For.KeyRange(0L..1000L)) }
        }

        test("simple test") {
            val umn = IndexingUMN(storageProvider(), setOf(idxToLong))
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expect(6) { umn.getIndex(idxToLong).getCount(For.KeyRange(50L..55L)) }
            expect(100) { umn.getIndex(idxToLong).getCount(For.KeyRange(0L..99L)) }
        }

        test("multiple documents with the same key") {
            val umn = IndexingUMN(storageProvider(), setOf(idxLength))
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

    group("findAllDocsPaged()") {
        test("check that the result documents are returned in a correct order") {
            val umn = IndexingUMN(storageProvider(), setOf(idxToLong))
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expectList("50", "51", "52", "53", "54", "55") { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(50L..55L)).values.toList() }
            expectList("1000") { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(1000L..2000L)).values.toList() }
            expectList() { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(1001L..2000L)).values.toList() }
            expectList() { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(-1000L..-10L)).values.toList() }
            expectList() { umn.getIndex(idxToLong).findAllDocsPaged(For.KeySet()).values.toList() }
            expectList() { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(1000L..2000L), 5..4).values.toList() }
        }

        test("check offset") {
            val umn = IndexingUMN(storageProvider(), setOf(idxToLong))
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expectList("52", "53") { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(50L..55L), 2..3).values.toList() }
            expectList("50", "51") { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(50L..55L), 0..1).values.toList() }
        }

        test("multiple values mapping to a Long key") {
            val umn = IndexingUMN(storageProvider(), setOf(IndexingUMN.IndexDef.simple("toLong", { doc -> setOf(doc.toLong() / 3) }, String::class)))
            (0..1000).forEach { umn.create(it.toString()) }
            expectList("48", "49", "50", "51", "52", "53", "54", "55", "56") { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(16L..18L)).values.sorted() }
            expectList() { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(1000L..2000L)).values.toList() }
            expectList() { umn.getIndex(idxToLong).findAllDocsPaged(For.KeyRange(-1000L..-10L)).values.toList() }
        }

        test("using CKey") {
            val idx = IndexingUMN.IndexDef.simple("index", { doc -> setOf(doc) }, CKey::class)
            val umn = IndexingUMN(storageProvider(), setOf(idx))

            (0..1000).forEach { umn.create(CKey("foo", it.toFloat())) }
            (0..1000).forEach { umn.create(CKey("bar", it.toFloat())) }
            (0..1000).forEach { umn.create(CKey("baz", it.toFloat())) }
            expectList() { umn.getIndex(idx).findAllDocsPaged(For.KeyRange(CKey("kvak", 0f)..CKey("kvak", 10f))).values.sorted() }
            expectList() { umn.getIndex(idx).findAllDocsPaged(For.KeyRange(CKey("bar", 2000f)..CKey("bar", 3000f))).values.sorted() }
            expect((0..10).map { it.toFloat() }) {
                umn.getIndex(idx).findAllDocsPaged(For.KeyRange(CKey("foo", 0f)..CKey("foo", 10f))).values.map { it.ordering }
            }
        }
    }

    group("findAllIDsPaged()") {
        test("check that the result documents are returned in a correct order") {
            val umn = IndexingUMN(storageProvider(), setOf(idxToLong))
            val ids: List<UUID> = (0..1000).map { umn.create(it.toString()) }
            expect(ids.subList(50, 56)) { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(50L..55L)).toList() }
            expectList(ids.last()) { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(1000L..2000L)).toList() }
            expectList() { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(1001L..2000L)).toList() }
            expectList() { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(-1000L..-10L)).toList() }
            expectList() { umn.getIndex(idxToLong).findAllIDsPaged(For.KeySet()).toList() }
            expectList() { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(1000L..2000L), 5..4).toList() }
        }

        test("check offset") {
            val umn = IndexingUMN(storageProvider(), setOf(idxToLong))
            val ids: List<UUID> = (0..1000).map { umn.create(it.toString()) }
            expect(ids.subList(52, 54)) { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(50L..55L), 2..3).toList() }
            expect(ids.subList(50, 52)) { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(50L..55L), 0..1).toList() }
        }

        test("multiple values mapping to a Long key") {
            val umn = IndexingUMN(storageProvider(), setOf(IndexingUMN.IndexDef.simple("toLong", { doc -> setOf(doc.toLong() / 3) }, String::class)))
            val ids: List<UUID> = (0..1000).map { umn.create(it.toString()) }
            expect(ids.subList(48, 57).toSet()) { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(16L..18L)).toSet() }
            expectList() { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(1000L..2000L)).toList() }
            expectList() { umn.getIndex(idxToLong).findAllIDsPaged(For.KeyRange(-1000L..-10L)).toList() }
        }

        test("using CKey") {
            val idx = IndexingUMN.IndexDef.simple("index", { doc -> setOf(doc) }, CKey::class)
            val umn = IndexingUMN(storageProvider(), setOf(idx))

            (0..1000).forEach { umn.create(CKey("foo", it.toFloat())) }
            (0..1000).forEach { umn.create(CKey("bar", it.toFloat())) }
            (0..1000).forEach { umn.create(CKey("baz", it.toFloat())) }
            expectList() { umn.getIndex(idx).findAllIDsPaged(For.KeyRange(CKey("kvak", 0f)..CKey("kvak", 10f))).toList() }
            expectList() { umn.getIndex(idx).findAllIDsPaged(For.KeyRange(CKey("bar", 2000f)..CKey("bar", 3000f))).toList() }
        }
    }

    group("allDocumentIdSequence()") {
        test("initially the iterator is empty") {
            val umn = IndexingUMN(storageProvider(), indices)
            expectList() { umn.getIndex(idxIdentity).getSequenceOfIDs(For.all()).toList() }
            expectList() { umn.getIndex(idxLength).getSequenceOfIDs(For.all()).toList() }
        }
        test("iterator lists all documents") {
            val umn = IndexingUMN(storageProvider(), indices)
            val ids: Set<UUID> = (0..100).map { umn.create("$it") } .toSet()
            expect(ids) { umn.getIndex(idxIdentity).getSequenceOfIDs(For.all()).toSet() }
            expect(ids) { umn.getIndex(idxLength).getSequenceOfIDs(For.all()).toSet() }
        }
        test("iterator doesn't list removed documents") {
            val umn = IndexingUMN(storageProvider(), indices)
            val ids: Set<UUID> = (0..10).map { umn.create("$it") } .toSet()
            ids.forEach { umn.delete(it) }
            expect(null) { umn.getIndex(idxIdentity).findFirstID(For.Key("0")) }
            expectList() { umn.getIndex(idxIdentity).getSequenceOfIDs(For.all()).toList() }
            expect(null) { umn.getIndex(idxLength).findFirstID(For.Key(1)) }
            expectList() { umn.getIndex(idxLength).getSequenceOfIDs(For.all()).toList() }
        }
        test("iterating IDs doesn't fail even when the documents are being deleted") {
            val umn = IndexingUMN(storageProvider(), indices)
            val ids = (0..9).map { umn.create("$it") } .toSet()
            val collected = umn.getIndex(idxIdentity).getSequenceOfIDs(For.all()).onEach {
                ids.forEach { id -> umn.delete(id) }
            } .toList()
            expect(ids.toList()) { collected }
            expect(null) { umn.getIndex(idxIdentity).findFirstID(For.Key("0")) }
            expectList() { umn.getIndex(idxIdentity).getSequenceOfIDs(For.all()).toList() }
            expect(null) { umn.getIndex(idxLength).findFirstID(For.Key(1)) }
            expectList() { umn.getIndex(idxLength).getSequenceOfIDs(For.all()).toList() }
        }
    }

    group("documentIdSequenceByKeyRange()") {
        test("initially the iterator is empty") {
            val umn = IndexingUMN(storageProvider(), indices)
            expectList() { umn.getIndex(idxIdentity).getSequenceOfIDs(For.KeyRange("0".."20")).toList() }
            expectList() { umn.getIndex(idxLength).getSequenceOfIDs(For.KeyRange(0..3)).toList() }
        }
        test("iterator lists all documents") {
            val umn = IndexingUMN(storageProvider(), indices)
            val ids: Set<UUID> = (0..11).map { umn.create("$it") } .toSet()
            expect(ids) { umn.getIndex(idxIdentity).getSequenceOfIDs(For.KeyRange("".."9999")).toSet() }
            expect(ids) { umn.getIndex(idxLength).getSequenceOfIDs(For.KeyRange(0..10)).toSet() }
        }
        test("iterator lists only matching documents") {
            val umn = IndexingUMN(storageProvider(), indices)
            val ids: Set<UUID> = (0..10).map { umn.create("$it") } .toSet()
            expect(ids.toList().subList(0, 10)) { umn.getIndex(idxLength).getSequenceOfIDs(For.KeyRange(0..1)).toList() }
            expect(ids.toList().subList(10, 11)) { umn.getIndex(idxLength).getSequenceOfIDs(For.KeyRange(2..2)).toList() }
        }
        test("iterator doesn't list removed documents") {
            val umn = IndexingUMN(storageProvider(), indices)
            val ids: Set<UUID> = (0..10).map { umn.create("$it") } .toSet()
            ids.forEach { umn.delete(it) }
            expect(null) { umn.getIndex(idxIdentity).findFirstID(For.Key("0")) }
            expectList() { umn.getIndex(idxIdentity).getSequenceOfIDs(For.KeyRange("0".."1000")).toList() }
            expect(null) { umn.getIndex(idxLength).findFirstID(For.Key(1)) }
            expectList() { umn.getIndex(idxLength).getSequenceOfIDs(For.KeyRange(0..10)).toList() }
        }
        test("iterating IDs doesn't fail even when the documents are being deleted") {
            val umn = IndexingUMN(storageProvider(), indices)
            val ids = (0..9).map { umn.create("$it") } .toSet()
            val collected = umn.getIndex(idxIdentity).getSequenceOfIDs(For.all()).onEach {
                ids.forEach { id -> umn.delete(id) }
            } .toList()
            expect(ids.toList()) { collected }
            expect(null) { umn.getIndex(idxIdentity).findFirstID(For.Key("0")) }
            expectList() { umn.getIndex(idxIdentity).getSequenceOfIDs(For.KeyRange("0".."1000")).toList() }
            expect(null) { umn.getIndex(idxLength).findFirstID(For.Key(1)) }
            expectList() { umn.getIndex(idxLength).getSequenceOfIDs(For.KeyRange(0..10)).toList() }
        }
    }

    test("Indices should ignore incompatible documents") {
        // all indices expect Strings. Let's feed them Long, they should ignore it.
        val umn = IndexingUMN(storageProvider(), indices)
        val id = umn.create(25L)
        umn.update(id, 45L as java.lang.Long)
        expect(45L as java.lang.Long) { umn.get(id, java.lang.Long::class) }
        umn.delete(id)
        expect(null) { umn.find(id, java.lang.Long::class) }
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
            ids.forEach { umn.delete(it) }
            expect(true) { umn.getIndex(idxIdentity).isEmpty() }
            expect(true) { umn.getIndex(idxLength).isEmpty() }
        }
    }

    group("complex index") {
        test("fast parent retrieval") {
            // in this example we're going to store the parent IDs instead of IDs of the children, to allow fast parent retrieval.
            // Key will be the name of the child.
            data class Parent(val name: String) : Serializable
            data class Child(val name: String, val parentId: UUID) : Serializable

            val idxParent = IndexingUMN.IndexDef<String, Child, Parent>("quick_parent_lookup",
                { doc -> setOf(doc.name) }, Child::class, Parent::class, { _, doc -> doc.parentId })
            val umn = IndexingUMN(storageProvider(), setOf(idxParent))
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

        test("Indices should ignore incompatible documents") {
            // in this example we're going to store the parent IDs instead of IDs of the children, to allow fast parent retrieval.
            // Key will be the name of the child.
            data class Parent(val name: String) : Serializable
            data class Child(val name: String, val parentId: UUID) : Serializable

            val idxParent = IndexingUMN.IndexDef<String, Child, Parent>("quick_parent_lookup",
                { doc -> setOf(doc.name) }, Child::class, Parent::class, { _, doc -> doc.parentId })

            // all indices expect Child. Let's feed them Long, they should ignore it.
            val umn = IndexingUMN(storageProvider(), indices)
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
class IndexingUMNTest : DynaTest({
    // doesn't test the indexing capabilities per se; it tests whether the IndexingUMN still behaves as a proper UMN storage.
    group("basic tests") {
        group("in-memory") {
            genericUmnTestBattery {
                val indices = setOf(IndexingUMN.IndexDef.simple("identityHashCode", { doc -> setOf(System.identityHashCode(doc)) }, Any::class))
                IndexingUMN(InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer), indices)
            }
        }
        group("on mvstore") {
            genericUmnTestBattery {
                val indices = setOf(IndexingUMN.IndexDef.simple("identityHashCode", { doc -> setOf(System.identityHashCode(doc)) }, Any::class))
                val mvstore = createMvstoreUMN()
                IndexingUMN(mvstore, indices)
            }
        }
    }

    // tests the indexing capabilities of the IndexingUMN
    group("index tests") {
        group("in-memory") {
            indexingUmnTestBattery { InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer) }
        }
        group("on mvstore") {
            indexingUmnTestBattery { createMvstoreUMN() }
        }
    }
})
