package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.expectList
import com.github.mvysny.dynatest.expectThrows
import java.util.*
import kotlin.test.expect

/**
 * A generic test battery to test basic properties of a UMN storage.
 */
fun DynaNodeGroup.genericUmnTestBattery(emptyStorageProvider: () -> UMN) {
    lateinit var umn: UMN
    beforeEach { umn = emptyStorageProvider() }
    afterEach { umn.close() }

    test("document initially does not exist") {
        val id = UUID.randomUUID()
        expect(false) { umn.exists(id) }
        expect(null) { umn.find(id, String::class) }
    }
    group("create") {
        test("create of document should succeed") {
            umn.create("testing document")
        }
        test("created document should exist") {
            val id = umn.create("testing document")
            expect(true) { umn.exists(id) }
        }
        test("re-creating same document does nothing") {
            val id = umn.create("testing document")
            expect(false) { umn.create("foo", id) }
            expect("testing document") { umn.get(id, String::class) }
        }
    }
    test("re-reading of created document should succeed") {
        val id = umn.create("testing document")
        expect("testing document") { umn.find(id, String::class)!! }
    }
    group("update") {
        test("created document should be updatable") {
            val id = umn.create("testing document")
            umn.update(id, "foo", String::class)
            expect("foo") { umn.get(id, String::class) }
        }
        test("update of non-existing document should fail") {
            expectThrows(IllegalArgumentException::class) {
                umn.update(UUID.randomUUID(), "foo", String::class)
            }
        }
    }
    test("document no longer exists after delete") {
        val id = umn.create("testing document")
        umn.delete(id, String::class)
        expect(false) { umn.exists(id) }
        expect(null) { umn.find(id, String::class) }
    }

    group("UtilityMap tests") {
        lateinit var map: UtilityMap<Long, String>
        beforeEach { map = umn.openUtilityMap("testing") }

        test("The map is initially empty") {
            expect(null) { map.getLargestKey() }
            expect(null) { map.getSmallestKey() }
            (0..1000).forEach { key ->
                expect(null) { map.find(key.toLong()) }
            }
            expectList() { map.keyIterator(0L..10000L).asSequence().toList() }
        }

        test("Adding one item") {
            map.put(10L, "Hello world")
            expect(10L) { map.getLargestKey() }
            expect(10L) { map.getSmallestKey() }
            (0..1000).forEach { key ->
                expect(if (key == 10) "Hello world" else null) { map.find(key.toLong()) }
            }
            expectList(10L) { map.keyIterator(0L..10000L).asSequence().toList() }
        }

        test("Adding one item and removing it falls back to empty map") {
            map.put(10L, "Hello world")
            map.put(10L, null)
            expect(null) { map.getLargestKey() }
            expect(null) { map.getSmallestKey() }
            expectList() { map.keyIterator(0L..10000L).asSequence().toList() }
        }

        test("values() returns all values") {
            map.put(10L, "Hello world")
            map.put(20L, "Goodbye world")
            expect(listOf("Goodbye world", "Hello world")) { map.values().asSequence().toList().sorted() }
        }

        test("values() is empty for an empty map") {
            expect(listOf()) { map.values().asSequence().toList() }
        }

        test("clearing the map removes all keys") {
            map.put(10L, "Hello world")
            map.clear()
            expect(null) { map.getLargestKey() }
            expect(null) { map.getSmallestKey() }
        }

        test("iterating values works even if the mappings are meanwhile deleted") {
            map.put(10L, "Hello world")
            map.put(20L, "Goodbye world")
            var count = 0
            map.values().forEach { map.put(10L, null); count++ }
            map.put(20L, null)
            expect(null) { map.getLargestKey() }
            expect(null) { map.getSmallestKey() }
            expect(2) { count }
        }
    }
}
