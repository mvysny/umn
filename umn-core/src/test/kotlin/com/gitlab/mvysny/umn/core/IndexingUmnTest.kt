package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectList
import org.h2.mvstore.MVStore
import java.io.Serializable
import kotlin.test.expect

/**
 * A testing battery which tests [IndexingUmn] backed by [storageProvider].
 */
fun DynaNodeGroup.indexingUmnTestBattery(storageProvider: ()->Umn) {
    lateinit var storage: Umn
    beforeEach { storage = storageProvider() }
    afterEach { storage.close() }

    group("findOneByKey()") {
        test("returns null on empty map") {
            val umn = IndexingUmn("identity", storageProvider(), { doc -> setOf(doc as String) })
            expect(null) { umn.findOneByKey("", String::class) }
            expect(null) { umn.findOneByKey("foo", String::class) }
        }

        test("correctly finds the document") {
            val umn = IndexingUmn("identity", storageProvider(), { doc -> setOf(doc as String) })
            umn.create("kvak")
            expect("kvak") { umn.findOneByKey("kvak", String::class) }
        }

        test("correctly updates the indices on update()") {
            val umn = IndexingUmn("identity", storageProvider(), { doc -> setOf(doc as String) })
            val id = umn.create("kvak")
            umn.update(id, "foo", String::class)
            expect("foo") { umn.findOneByKey("foo", String::class) }
            expect(null) { umn.findOneByKey("kvak", String::class) }
        }
    }

    group("findAllByKeyRange()") {
        test("on simple Long key") {
            val umn = IndexingUmn("toLong", storageProvider(), { doc -> setOf((doc as String).toLong()) })
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expectList("50", "51", "52", "53", "54", "55") { umn.findAllByKeyRange(50L..55L, String::class) }
            expectList("1000") { umn.findAllByKeyRange(1000L..2000L, String::class) }
            expectList() { umn.findAllByKeyRange(1001L..2000L, String::class) }
            expectList() { umn.findAllByKeyRange(-1000L..-10L, String::class) }
        }

        test("multiple values mapping to a Long key") {
            val umn = IndexingUmn("toLong", storageProvider(), { doc -> setOf((doc as String).toLong() / 3) })
            (0..1000).forEach { umn.create(it.toString()) }
            expectList("48", "49", "50", "51", "52", "53", "54", "55", "56") { umn.findAllByKeyRange(16L..18L, String::class) }
            expectList() { umn.findAllByKeyRange(1000L..2000L, String::class) }
            expectList() { umn.findAllByKeyRange(-1000L..-10L, String::class) }
        }

        test("using CKey") {
            val umn = IndexingUmn("index", storageProvider(), { doc -> setOf(doc as CKey) })
            (0..1000).forEach { umn.create(CKey("foo", it.toFloat())) }
            (0..1000).forEach { umn.create(CKey("bar", it.toFloat())) }
            (0..1000).forEach { umn.create(CKey("baz", it.toFloat())) }
            expectList() { umn.findAllByKeyRange(CKey("kvak", 0f)..CKey("kvak", 10f), CKey::class) }
            expectList() { umn.findAllByKeyRange(CKey("bar", 2000f)..CKey("bar", 3000f), CKey::class) }
            expect((0..10).map { it.toFloat() }) {
                umn.findAllByKeyRange(CKey("foo", 0f)..CKey("foo", 10f), CKey::class).map { it.ordering }
            }
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
