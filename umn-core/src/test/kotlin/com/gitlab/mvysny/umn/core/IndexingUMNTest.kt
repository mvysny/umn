package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectList
import org.h2.mvstore.MVStore
import java.io.Serializable
import kotlin.test.expect

/**
 * A testing battery which tests [IndexingUMN] backed by [storageProvider].
 */
fun DynaNodeGroup.indexingUmnTestBattery(storageProvider: ()->UMN) {
    lateinit var storage: UMN
    beforeEach { storage = storageProvider() }
    afterEach { storage.close() }

    group("findOneByKey()") {
        test("returns null on empty map") {
            val umn = IndexingUMN("identity", storageProvider(), { doc -> setOf(doc as String) })
            expect(null) { umn.findOneByKey("", String::class) }
            expect(null) { umn.findOneByKey("foo", String::class) }
        }

        test("correctly finds the document") {
            val umn = IndexingUMN("identity", storageProvider(), { doc -> setOf(doc as String) })
            umn.create("kvak")
            expect("kvak") { umn.findOneByKey("kvak", String::class) }
        }

        test("correctly updates the indices on update()") {
            val umn = IndexingUMN("identity", storageProvider(), { doc -> setOf(doc as String) })
            val id = umn.create("kvak")
            umn.update(id, "foo", String::class)
            expect("foo") { umn.findOneByKey("foo", String::class) }
            expect(null) { umn.findOneByKey("kvak", String::class) }
        }
    }

    group("findAllByKeyRange()") {
        test("on simple Long key") {
            val umn = IndexingUMN("toLong", storageProvider(), { doc -> setOf((doc as String).toLong()) })
            (0..1000).shuffled().forEach { umn.create(it.toString()) }
            expectList("50", "51", "52", "53", "54", "55") { umn.findAllByKeyRange(50L..55L, String::class) }
            expectList("1000") { umn.findAllByKeyRange(1000L..2000L, String::class) }
            expectList() { umn.findAllByKeyRange(1001L..2000L, String::class) }
            expectList() { umn.findAllByKeyRange(-1000L..-10L, String::class) }
        }

        test("multiple values mapping to a Long key") {
            val umn = IndexingUMN("toLong", storageProvider(), { doc -> setOf((doc as String).toLong() / 3) })
            (0..1000).forEach { umn.create(it.toString()) }
            expectList("48", "49", "50", "51", "52", "53", "54", "55", "56") { umn.findAllByKeyRange(16L..18L, String::class) }
            expectList() { umn.findAllByKeyRange(1000L..2000L, String::class) }
            expectList() { umn.findAllByKeyRange(-1000L..-10L, String::class) }
        }

        test("using CKey") {
            val umn = IndexingUMN("index", storageProvider(), { doc -> setOf(doc as CKey) })
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
 * Tests the [IndexingUMN] class.
 */
class IndexingUMNTest : DynaTest({
    // doesn't test the indexing capabilities per se; it tests whether the IndexingUMN still behaves as a proper UMN storage.
    group("basic tests") {
        group("in-memory") {
            genericUmnTestBattery {
                val indexGenerator: (Any) -> Set<Int> = { doc -> setOf(System.identityHashCode(doc)) }
                IndexingUMN("identityHashCode", InMemoryUMN(), indexGenerator)
            }
        }
        group("on mvstore") {
            genericUmnTestBattery {
                val indexGenerator: (Any) -> Set<Int> = { doc -> setOf(System.identityHashCode(doc)) }
                val mvstore = MvstoreUMN(MVStore.open(createTempFile().absolutePath), JavaDocumentSerializer)
                IndexingUMN("identityHashCode", mvstore, indexGenerator)
            }
        }
    }

    // tests the indexing capabilities of the IndexingUMN
    group("index tests") {
        group("in-memory") {
            indexingUmnTestBattery { InMemoryUMN() }
        }
        group("on mvstore") {
            indexingUmnTestBattery { MvstoreUMN(MVStore.open(createTempFile().absolutePath), JavaDocumentSerializer) }
        }
    }
})
