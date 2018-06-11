package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectList
import org.h2.mvstore.MVStore
import kotlin.test.expect

/**
 * A testing battery which tests [IndexingUmn] backed by [storageProvider].
 */
fun DynaNodeGroup.indexingUmnTestBattery(storageProvider: ()->Umn) {
    lateinit var storage: Umn
    beforeEach { storage = storageProvider() }
    afterEach { storage.close() }

    test("findOneByKey returns null on empty map") {
        val umn = IndexingUmn("identity", storageProvider(), { doc -> setOf(doc as String) })
        expect(null) { umn.findOneByKey("", String::class) }
        expect(null) { umn.findOneByKey("foo", String::class) }
    }

    test("findOneByKey correctly finds the document") {
        val umn = IndexingUmn("identity", storageProvider(), { doc -> setOf(doc as String) })
        umn.create("kvak")
        expect("kvak") { umn.findOneByKey("kvak", String::class) }
    }

    test("findOneByKey correctly updates the indices on update()") {
        val umn = IndexingUmn("identity", storageProvider(), { doc -> setOf(doc as String) })
        val id = umn.create("kvak")
        umn.update(id, "foo", String::class)
        expect("foo") { umn.findOneByKey("foo", String::class) }
        expect(null) { umn.findOneByKey("kvak", String::class) }
    }

    test("findAllByKeyRange() on simple Long key") {
        val umn = IndexingUmn("toLong", storageProvider(), { doc -> setOf((doc as String).toLong()) })
        (0..1000).forEach { umn.create(it.toString()) }
        expectList("50", "51", "52", "53", "54", "55") { umn.findAllByKeyRange(50L..55L, String::class) }
    }
}

/**
 * Tests the [IndexingUmn] class.
 */
class IndexingUmnTest : DynaTest({
    // doesn't test the indexing capabilities per se; it tests whether the IndexingUmn still behaves as a proper Umn storage.
    group("basic tests") {
        group("in-memory") {
            genericUmnTestBattery {
                val indexGenerator: (Any) -> Set<Int> = { doc -> setOf(System.identityHashCode(doc)) }
                IndexingUmn("identityHashCode", InMemoryUmn(), indexGenerator)
            }
        }
        group("on mvstore") {
            genericUmnTestBattery {
                val indexGenerator: (Any) -> Set<Int> = { doc -> setOf(System.identityHashCode(doc)) }
                val mvstore = MvstoreUmn(MVStore.open(createTempFile().absolutePath), JavaDocumentSerializer)
                IndexingUmn("identityHashCode", mvstore, indexGenerator)
            }
        }
    }

    // tests the indexing capabilities of the IndexingUmn
    group("index tests") {
        group("in-memory") {
            indexingUmnTestBattery { InMemoryUmn() }
        }
        group("on mvstore") {
            indexingUmnTestBattery { MvstoreUmn(MVStore.open(createTempFile().absolutePath), JavaDocumentSerializer) }
        }
    }
})
