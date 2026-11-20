package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.*
import org.h2.mvstore.MVStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.expect

class MutationLogTest {
    @Nested inner class InMemory : MutationLogTestBattery() {
        override fun newStorage(): UMN = MvstoreUMN.inMemory(JavaDocumentSerializer, AutoTypeResolver)
    }
    @Nested inner class OnMvstore : MutationLogTestBattery() {
        override fun newStorage(): UMN = createMvstoreUMN()
    }

    @Nested inner class KillMidPopulation {
        @TempDir lateinit var dir: File
        private val file: File get() = File(dir, "node")

        /**
         * Creates 100 documents, then runs [populate] killed after the 50th document: a store, as MVStore's background writer
         * makes, then the kill.
         * @return the documents' IDs.
         */
        private fun killMidPopulation(populate: (UMN) -> Unit): List<UUID> {
            val store = MVStore.Builder().fileName(file.path).autoCommitDisabled().open()
            val node = MvstoreUMN(store, JavaDocumentSerializer, AutoTypeResolver)
            val ids = (1..100).map { node.create("doc $it") }
            node.commit()
            assertFailsWith<KilledException> { populate(KilledMidWalk(node, after = 50)) }
            store.closeImmediately()
            return ids
        }

        private fun bufferedIds(forceRepopulate: Boolean = false): Set<UUID> = createMvstoreUMN(file.path).use { reopened ->
            MutationLog(reopened, "OUTGOING_BUFFER", JavaDocumentSerializer, forceRepopulate).drain().map { it.id }.toSet()
        }

        @Test fun `the next open populates every document`() {
            val ids = killMidPopulation { MutationLog(it, "OUTGOING_BUFFER", JavaDocumentSerializer, false) }
            expect(ids.toSet()) { bufferedIds() }
        }

        @Test fun `the next open repopulates every document, after a kill mid-repopulation`() {
            createMvstoreUMN(file.path).use { MutationLog(it, "OUTGOING_BUFFER", JavaDocumentSerializer, false).drain() }
            val ids = killMidPopulation { MutationLog(it, "OUTGOING_BUFFER", JavaDocumentSerializer, true) }
            expect(ids.toSet()) { bufferedIds() }
        }

        @Test fun `a kill right after the population keeps it`() {
            val store = MVStore.Builder().fileName(file.path).autoCommitDisabled().open()
            val node = MvstoreUMN(store, JavaDocumentSerializer, AutoTypeResolver)
            node.create("doc")
            MutationLog(node, "OUTGOING_BUFFER", JavaDocumentSerializer, false)
            store.closeImmediately()

            var populated = false
            createMvstoreUMN(file.path).use { MutationLog(it, "OUTGOING_BUFFER", JavaDocumentSerializer, false) { populated = true } }
            expect(false) { populated }
        }
    }
}

internal class KilledException : RuntimeException("simulated kill")

/**
 * Walks the documents of [delegate], [MvstoreUMN.commit]s after the [after]th and throws [KilledException].
 */
internal class KilledMidWalk(override val delegate: MvstoreUMN, private val after: Int) : UMN by delegate {
    override fun getSequenceOfSerializedDocs(): Sequence<Pair<UUID, SerializedDocument>> =
        delegate.getSequenceOfSerializedDocs().withIndex().map { (index, doc) ->
            if (index == after) {
                delegate.commit()
                throw KilledException()
            }
            doc
        }
}

/**
 * Tests the initial population of a [MutationLog]; run it like [GenericUmnTestBattery].
 */
abstract class MutationLogTestBattery {
    /**
     * Returns a new empty storage.
     */
    abstract fun newStorage(): UMN

    private lateinit var storage: UMN
    @BeforeEach fun createStorage() { storage = newStorage() }
    @AfterEach fun closeStorage() { storage.close() }
    @Nested inner class InitialPopulation {
        @Test fun `initial population ignores deleted documents`() {
            storage.delete(storage.create("kvak"))
            val log = storage.mutationLog(JavaDocumentSerializer).second
            expect(listOf()) { log.drain() }
        }
        @Test fun `drain cleans the log`() {
            val id = storage.create("kvak")
            storage.update(id, "foo")
            val log = storage.mutationLog(JavaDocumentSerializer).second
            expect(listOf(LogRecord.DocumentCreated("foo", AutoTypeResolver.toType(String::class), id))) { log.drain() }
            expect(listOf()) { log.drain() }
        }
        @Test fun `initial population does not pick document updates`() {
            val id = storage.create("kvak")
            storage.update(id, "foo")
            val log = storage.mutationLog(JavaDocumentSerializer).second
            expect(listOf(LogRecord.DocumentCreated("foo", AutoTypeResolver.toType(String::class), id))) { log.drain() }
        }
        @Test fun `initial population runs only once`() {
            val id = storage.create("kvak")
            var log = storage.mutationLog(JavaDocumentSerializer).second
            expect(listOf(LogRecord.DocumentCreated("kvak", AutoTypeResolver.toType(String::class), id))) { log.drain() }
            log = storage.mutationLog(JavaDocumentSerializer).second
            expect(listOf()) { log.drain() }
        }
        @Test fun `repopulation causes log to be populated two times`() {
            val id = storage.create("kvak")
            var log = storage.mutationLog(JavaDocumentSerializer).second
            expect(listOf(LogRecord.DocumentCreated("kvak", AutoTypeResolver.toType(String::class), id))) { log.drain() }
            log = storage.mutationLog(JavaDocumentSerializer, true).second
            expect(listOf(LogRecord.DocumentCreated("kvak", AutoTypeResolver.toType(String::class), id))) { log.drain() }
        }
    }
}