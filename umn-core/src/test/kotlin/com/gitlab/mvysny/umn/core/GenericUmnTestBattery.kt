package com.gitlab.mvysny.umn.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.assertFailsWith
import kotlin.test.expect

/**
 * A generic test battery to test basic properties of a UMN storage; run it as a nested class:
 *
 * ```
 * @Nested inner class InMemory : GenericUmnTestBattery() {
 *     override fun newUmn(): UMN = MvstoreUMN.inMemory(JavaDocumentSerializer, AutoTypeResolver)
 * }
 * ```
 *
 * The battery's own `@BeforeEach` calls [newUmn], before any `@BeforeEach` of a subclass:
 * set up what [newUmn] needs in an enclosing class.
 */
abstract class GenericUmnTestBattery {
    /**
     * Returns a new empty storage.
     */
    abstract fun newUmn(): UMN

    private lateinit var umn: UMN
    @BeforeEach fun createUmn() { umn = newUmn() }
    @AfterEach fun closeUmn() { umn.close() }

    @Test fun `document initially does not exist`() {
        val id = UUID.randomUUID()
        expect(false) { umn.exists(id) }
        expect(null) { umn.find(id, String::class) }
        expect(true) { umn.isEmpty() }
    }
    @Nested inner class Create {
        @Test fun `create of document should succeed`() {
            umn.create("testing document")
        }
        @Test fun `created document should exist`() {
            val id = umn.create("testing document")
            expect(true) { umn.exists(id) }
            expect(false) { umn.isEmpty() }
        }
        @Test fun `re-creating same document does nothing`() {
            val id = umn.create("testing document")
            expect(false) { umn.create("foo", id) }
            expect("testing document") { umn.get(id, String::class) }
        }
    }
    @Test fun `re-reading of created document should succeed`() {
        val id = umn.create("testing document")
        expect("testing document") { umn.find(id, String::class)!! }
    }
    @Test fun `a boxed document reads back through its Kotlin primitive class`() {
        val id = umn.create(25L)
        expect(25L) { umn.get(id, Long::class) }
        expect(25L) { umn.find(id, Long::class) }
    }
    @Nested inner class Update {
        @Test fun `created document should be updatable`() {
            val id = umn.create("testing document")
            umn.update(id, "foo")
            expect("foo") { umn.get(id, String::class) }
        }
        @Test fun `update of non-existing document should fail`() {
            assertFailsWith<IllegalArgumentException> {
                umn.update(UUID.randomUUID(), "foo")
            }
        }
    }
    @Test fun `document no longer exists after delete`() {
        val id = umn.create("testing document")
        umn.delete(id)
        expect(false) { umn.exists(id) }
        expect(null) { umn.find(id, String::class) }
        expect(true) { umn.isEmpty() }
    }

    @Nested inner class UtilityMapTests {
        private lateinit var map: UtilityMap<Long, String>
        @BeforeEach fun openMap() { map = umn.openUtilityMap("testing") }

        @Test fun `utilityMapNames() lists the maps opened, not those dropped`() {
            umn.openUtilityMap<Long, String>("other")
            expect(setOf("testing", "other")) { umn.utilityMapNames() intersect setOf("testing", "other", "never opened") }
            umn.dropUtilityMap("other")
            expect(setOf("testing")) { umn.utilityMapNames() intersect setOf("testing", "other") }
        }

        @Test fun `The map is initially empty`() {
            expect(null) { map.getLargestKey() }
            expect(null) { map.getSmallestKey() }
            expect(true) { map.isEmpty() }
            expect(listOf()) { map.keyIterator().toList() }
            expect(listOf()) { map.keyPage(0..10000).toList() }
            expect(listOf()) { map.keyPage(IntRange.EMPTY).toList() }
            (0..1000).forEach { key ->
                expect(null) { map.find(key.toLong()) }
            }
            expect(listOf()) { map.keyIterator(0L..10000L).asSequence().toList() }
            expect(0) { map.keyCount() }
        }

        @Test fun `Adding one item`() {
            map.put(10L, "Hello world")
            expect(10L) { map.getLargestKey() }
            expect(10L) { map.getSmallestKey() }
            expect(listOf(10L)) { map.keyIterator().toList() }
            expect(listOf(10L)) { map.keyPage(0..10000).toList() }
            expect(listOf()) { map.keyPage(IntRange.EMPTY).toList() }
            (0..1000).forEach { key ->
                expect(if (key == 10) "Hello world" else null) { map.find(key.toLong()) }
            }
            expect(listOf(10L)) { map.keyIterator(0L..10000L).asSequence().toList() }
            expect(false) { map.isEmpty() }
            expect(1) { map.keyCount() }
        }

        @Test fun `Adding one item and removing it falls back to empty map`() {
            map.put(10L, "Hello world")
            map.put(10L, null)
            expect(null) { map.getLargestKey() }
            expect(null) { map.getSmallestKey() }
            expect(listOf()) { map.keyIterator(0L..10000L).asSequence().toList() }
            expect(listOf()) { map.keyIterator().toList() }
            expect(listOf()) { map.keyPage(0..10000).toList() }
            expect(listOf()) { map.keyPage(IntRange.EMPTY).toList() }
            expect(0) { map.keyCount() }
        }

        @Test fun `values() returns all values`() {
            map.put(10L, "Hello world")
            map.put(20L, "Goodbye world")
            expect(listOf("Goodbye world", "Hello world")) { map.values().asSequence().toList().sorted() }
            expect(2) { map.keyCount() }
            expect(listOf(10L, 20L)) { map.keyPage(0..10000).toList() }
            expect(listOf()) { map.keyPage(IntRange.EMPTY).toList() }
        }

        @Test fun `values() is empty for an empty map`() {
            expect(listOf()) { map.values().asSequence().toList() }
        }

        @Test fun `clearing the map removes all keys`() {
            map.put(10L, "Hello world")
            map.clear()
            expect(null) { map.getLargestKey() }
            expect(null) { map.getSmallestKey() }
            expect(true) { map.isEmpty() }
            expect(listOf()) { map.keyIterator().toList() }
            expect(listOf()) { map.keyPage(0..10000).toList() }
            expect(listOf()) { map.keyPage(IntRange.EMPTY).toList() }
            expect(0) { map.keyCount() }
        }

        @Test fun `iterating values works even if the mappings are meanwhile deleted`() {
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

        @Test fun `lowerKey()`() {
            (0..9L).forEach { map.put(it, "$it") }
            expect(7) { map.lowerKey(8) }
            expect(8) { map.lowerKey(9) }
            expect(1) { map.lowerKey(2) }
            expect(0) { map.lowerKey(1) }
            expect(null) { map.lowerKey(0) }
        }
    }

    @Nested inner class GetSequenceOfIDs {
        @Test fun `initially it returns an empty value`() {
            expect(listOf()) { umn.getSequenceOfIDs().toList() }
        }
        @Test fun `creating document lists it in the sequence`() {
            val id = umn.create("any")
            expect(listOf(id)) { umn.getSequenceOfIDs().toList() }
        }
        @Test fun `creating+deleting document does not lists it in the sequence`() {
            val id = umn.create("any")
            umn.delete(id)
            expect(listOf()) { umn.getSequenceOfIDs().toList() }
        }
    }
}
