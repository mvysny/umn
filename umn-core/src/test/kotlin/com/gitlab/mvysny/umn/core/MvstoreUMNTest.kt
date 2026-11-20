package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVMap
import org.h2.mvstore.MVStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.io.DataInputStream
import java.time.Duration
import java.util.*
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.assertTrue
import kotlin.test.expect
import java.io.File

class MvstoreUMNTest {
    @Nested inner class InMemoryTests : GenericUmnTestBattery() {
        override fun newUmn(): UMN = MvstoreUMN(MVStore.open(null), JavaDocumentSerializer, AutoTypeResolver)
    }

    @Nested inner class FileBasedTests : GenericUmnTestBattery() {
        override fun newUmn(): UMN = createMvstoreUMN()
    }

    @Test fun `compact keeps the documents`() {
        createMvstoreUMN().use { umn ->
            expect(null) { umn.delegate }
            val ids = (0..100).map { umn.create("doc $it") }
            ids.take(50).forEach { umn.delete(it) }
            umn.compact()
            expect("doc 50") { umn.get(ids[50], String::class) }
            expect(51) { umn.getSequenceOfIDs().count() }
        }
    }

    @Test fun `compact returns, shrinking a store whose documents are all live`() {
        val file = File.createTempFile("compact", ".mv")
        val store = MVStore.Builder().fileName(file.path).open()
        MvstoreUMN(store, JavaDocumentSerializer, AutoTypeResolver).use { umn ->
            val random = Random(42)
            val a = umn.openUtilityMap<Int, ByteArray>("a")
            val b = umn.openUtilityMap<Int, ByteArray>("b")
            val ids = List(30_000) { i ->
                a.put(i, random.nextBytes(100))
                b.put(i, random.nextBytes(100))
                umn.create("doc $i".padEnd(100)).also { if (i % 100 == 99) store.commit() }
            }
            val before = file.length()
            assertTimeoutPreemptively(Duration.ofSeconds(30)) { umn.compact() }
            assertTrue(file.length() < before / 2, "${file.length()} vs $before")
            expect("doc 29999".padEnd(100)) { umn.get(ids.last(), String::class) }
            expect(30_000) { umn.getSequenceOfIDs().count() }
            expect(30_000) { a.keyCount() }
        }
        file.delete()
    }

    @Test fun `threads opening a new utility map at once all write into one map`() {
        MvstoreUMN(MVStore.open(null), JavaDocumentSerializer, AutoTypeResolver).use { umn ->
            repeat(20) { round ->
                val barrier = CyclicBarrier(8)
                List(8) { i ->
                    thread {
                        barrier.await()
                        umn.openUtilityMap<Int, String>("race$round").put(i, "foo")
                    }
                }.forEach { it.join() }
                expect(8, "round $round") { umn.openUtilityMap<Int, String>("race$round").keyCount() }
            }
        }
    }

    @Nested inner class MigrationV1V2Tests {
        private lateinit var store: MVStore
        private lateinit var root: MVMap<UUID, ByteArray>
        private lateinit var umn: MvstoreUMN
        @BeforeEach fun createUmn() {
            store = MVStore.open(File.createTempFile("tmp", null).absolutePath)
            root = store.openMap<UUID, ByteArray>("ROOT")
            umn = MvstoreUMN(store, JavaDocumentSerializer, AutoTypeResolver)
        }
        @AfterEach fun closeUmn() { umn.close() }

        @Test fun `migration runs on empty db`() {
            umn.migrateV1V2 { org.junit.jupiter.api.fail { "should not be called since there are no records" } }
            umn.migrateV1V2 { org.junit.jupiter.api.fail { "should not be called since there are no records" } }
        }

        @Test fun `migration runs on simple db`() {
            val id = UUID.randomUUID()
            root[id] = JavaDocumentSerializer.serialize("foo")
            umn.migrateV1V2 { uuid ->
                expect(id) { uuid }
                AutoTypeResolver.toType(String::class)
            }
            expect(0) { root.size }
            expect("foo") { umn.get(id, String::class) }
            expect(AutoTypeResolver.toType(String::class)) { umn.findSerialized(id)!!.type }
        }

        @Test fun `migration runs only once`() {
            val id = UUID.randomUUID()
            root[id] = JavaDocumentSerializer.serialize("foo")
            umn.migrateV1V2 { _ -> AutoTypeResolver.toType(String::class) }
            expect("foo") { umn.get(id, String::class) }
            umn.update(id, "bar")
            umn.migrateV1V2 { org.junit.jupiter.api.fail { "should not be called since there are no records" } }
            expect("bar") { umn.get(id, String::class) }
        }
    }
}

private val SerializedDocument.type: Short
    get() = DataInputStream(inputStream()).readShort()
