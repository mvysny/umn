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
        expect(true) { umn.isEmpty() }
    }
    group("create") {
        test("create of document should succeed") {
            umn.create("testing document")
        }
        test("created document should exist") {
            val id = umn.create("testing document")
            expect(true) { umn.exists(id) }
            expect(false) { umn.isEmpty() }
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
            umn.update(id, "foo")
            expect("foo") { umn.get(id, String::class) }
        }
        test("update of non-existing document should fail") {
            expectThrows(IllegalArgumentException::class) {
                umn.update(UUID.randomUUID(), "foo")
            }
        }
    }
    test("document no longer exists after delete") {
        val id = umn.create("testing document")
        umn.delete(id)
        expect(false) { umn.exists(id) }
        expect(null) { umn.find(id, String::class) }
        expect(true) { umn.isEmpty() }
    }

    group("UtilityMap tests") {
        lateinit var map: UtilityMap<Long, String>
        beforeEach { map = umn.openUtilityMap("testing") }

        test("The map is initially empty") {
            expect(null) { map.getLargestKey() }
            expect(null) { map.getSmallestKey() }
            expect(true) { map.isEmpty() }
            expectList() { map.keyIterator().toList() }
            expectList() { map.keyPage(0..10000).toList() }
            expectList() { map.keyPage(IntRange.EMPTY).toList() }
            (0..1000).forEach { key ->
                expect(null) { map.find(key.toLong()) }
            }
            expectList() { map.keyIterator(0L..10000L).asSequence().toList() }
            expect(0) { map.keyCount() }
        }

        test("Adding one item") {
            map.put(10L, "Hello world")
            expect(10L) { map.getLargestKey() }
            expect(10L) { map.getSmallestKey() }
            expectList(10L) { map.keyIterator().toList() }
            expectList(10L) { map.keyPage(0..10000).toList() }
            expectList() { map.keyPage(IntRange.EMPTY).toList() }
            (0..1000).forEach { key ->
                expect(if (key == 10) "Hello world" else null) { map.find(key.toLong()) }
            }
            expectList(10L) { map.keyIterator(0L..10000L).asSequence().toList() }
            expect(false) { map.isEmpty() }
            expect(1) { map.keyCount() }
        }

        test("Adding one item and removing it falls back to empty map") {
            map.put(10L, "Hello world")
            map.put(10L, null)
            expect(null) { map.getLargestKey() }
            expect(null) { map.getSmallestKey() }
            expectList() { map.keyIterator(0L..10000L).asSequence().toList() }
            expectList() { map.keyIterator().toList() }
            expectList() { map.keyPage(0..10000).toList() }
            expectList() { map.keyPage(IntRange.EMPTY).toList() }
            expect(0) { map.keyCount() }
        }

        test("values() returns all values") {
            map.put(10L, "Hello world")
            map.put(20L, "Goodbye world")
            expect(listOf("Goodbye world", "Hello world")) { map.values().asSequence().toList().sorted() }
            expect(2) { map.keyCount() }
            expectList(10L, 20L) { map.keyPage(0..10000).toList() }
            expectList() { map.keyPage(IntRange.EMPTY).toList() }
        }

        test("values() is empty for an empty map") {
            expect(listOf()) { map.values().asSequence().toList() }
        }

        test("clearing the map removes all keys") {
            map.put(10L, "Hello world")
            map.clear()
            expect(null) { map.getLargestKey() }
            expect(null) { map.getSmallestKey() }
            expect(true) { map.isEmpty() }
            expectList() { map.keyIterator().toList() }
            expectList() { map.keyPage(0..10000).toList() }
            expectList() { map.keyPage(IntRange.EMPTY).toList() }
            expect(0) { map.keyCount() }
        }

        test("iterating values works even if the mappings are meanwhile deleted") {
            (0..10L).forEach { map.put(it, "$it") }
            val encountered = mutableSetOf<Long>()
            for (value in map.values()) {
                encountered.add(value.toLong())
                (0..10L).forEach { map.put(it, null) }
            }
            expect(null) { map.getLargestKey() }
            expect(null) { map.getSmallestKey() }
            expect((0..10L).toList()) { encountered.toList() }
        }

        test("lowerKey()") {
            (0..9L).forEach { map.put(it, "$it") }
            expect(7) { map.lowerKey(8) }
            expect(8) { map.lowerKey(9) }
            expect(1) { map.lowerKey(2) }
            expect(0) { map.lowerKey(1) }
            expect(null) { map.lowerKey(0) }
        }
    }

    group("getSequenceOfIDs()") {
        test("initially it returns an empty value") {
            expectList() { umn.getSequenceOfIDs().toList() }
        }
        test("creating document lists it in the sequence") {
            val id = umn.create("any")
            expectList(id) { umn.getSequenceOfIDs().toList() }
        }
        test("creating+deleting document does not lists it in the sequence") {
            val id = umn.create("any")
            umn.delete(id)
            expectList() { umn.getSequenceOfIDs().toList() }
        }
    }
}
