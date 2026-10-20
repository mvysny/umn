package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.expect

class MutationLogTest {
    @Nested inner class InMemory : MutationLogTestBattery() {
        override fun newStorage(): UMN = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer)
    }
    @Nested inner class OnMvstore : MutationLogTestBattery() {
        override fun newStorage(): UMN = createMvstoreUMN()
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