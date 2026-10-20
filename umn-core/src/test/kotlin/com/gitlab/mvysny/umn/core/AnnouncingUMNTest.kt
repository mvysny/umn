package com.gitlab.mvysny.umn.core

import com.gitlab.mvysny.umn.sync.MutationLog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.assertFailsWith
import kotlin.test.expect

/**
 * Wraps this UMN in a capturing UMN which sends all mutation events to a log. Returns both the new capturing UMN and the log
 * which hosts the mutation events (in the form of [LogRecord]s).
 * @param forceRepopulate DANGEROUS: if true, the [fifolog] will be cleared out and repopulated from the database. This is helpful if we are to
 * sync to another sync server.
 */
internal fun UMN.mutationLog(serializer: DocumentSerializer, forceRepopulate: Boolean = false): Pair<AnnouncingUMN, MutationLog> {
    val log = MutationLog(this, "MUTATION_LOG", serializer, forceRepopulate)
    val umn = AnnouncingUMN(this, log)
    return umn to log
}

/**
 * A test battery which tests the [AnnouncingUMN], [MutationLog] and the serialization of [LogRecord]s;
 * run it like [GenericUmnTestBattery].
 */
abstract class AnnouncingTestBattery {
    /**
     * Returns a new empty storage.
     */
    abstract fun newStorage(): UMN

    private lateinit var umn: AnnouncingUMN
    private lateinit var log: MutationLog
    @BeforeEach fun createUmn() { newStorage().mutationLog(JavaDocumentSerializer).apply { umn = first; log = second } }
    @AfterEach fun closeUmn() { umn.close() }

    @Test fun `initially the log is empty`() {
        expect(listOf()) { log.drain() }
    }
    @Test fun `creating document produces one log entry`() {
        val id = umn.create("kvak")
        expect(listOf(LogRecord.DocumentCreated("kvak", AutoTypeResolver.toType(String::class), id))) { log.drain() }
    }
    @Test fun `creating+updating document produces two log entries`() {
        val id = umn.create("kvak")
        umn.update(id, "foo")
        expect(listOf(LogRecord.DocumentCreated("kvak", AutoTypeResolver.toType(String::class), id),
            LogRecord.DocumentUpdated("foo", AutoTypeResolver.toType(String::class), id))) { log.drain() }
    }
    @Test fun `creating+deleting document produces two log entries`() {
        val id = umn.create("kvak")
        umn.delete(id)
        expect(listOf(LogRecord.DocumentCreated("kvak", AutoTypeResolver.toType(String::class), id), LogRecord.DocumentDeleted(id))) { log.drain() }
    }
    @Test fun `draining two times in a row won't return the same items`() {
        val id = umn.create("kvak")
        umn.delete(id)
        expect(listOf(LogRecord.DocumentCreated("kvak", AutoTypeResolver.toType(String::class), id), LogRecord.DocumentDeleted(id))) { log.drain() }
        expect(listOf()) { log.drain() }
    }
}

/**
 * Tests the [AnnouncingUMN] class. Also tests the serialization of [LogRecord]s.
 */
class AnnouncingUMNTest {
    // doesn't test the indexing capabilities per se; it tests whether the IndexingUMN still behaves as a proper UMN storage.
    @Nested inner class BasicTests {
        @Nested inner class InMemory : GenericUmnTestBattery() {
            override fun newUmn(): UMN = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer).mutationLog(JavaDocumentSerializer).first
        }
        @Nested inner class OnMvstore : GenericUmnTestBattery() {
            override fun newUmn(): UMN = createMvstoreUMN().mutationLog(JavaDocumentSerializer).first
        }
    }

    @Nested inner class ReplayLogSpecificTests {
        @Nested inner class InMemory : AnnouncingTestBattery() {
            override fun newStorage(): UMN = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer)
        }
        @Nested inner class OnMvstore : AnnouncingTestBattery() {
            override fun newStorage(): UMN = createMvstoreUMN()
        }
    }

    @Test fun `findSerialized delegates`() {
        val storage = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer)
        val umn = AnnouncingUMN(storage, object : AnnouncingUMN.Listener {
            override fun onRecord(logRecord: LogRecord) {}
        })
        val id = umn.create("kvak")
        expect(storage.findSerialized(id)!!.toList()) { umn.findSerialized(id)!!.toList() }
    }

    @Nested inner class LogRecords {
        private val stringType = AutoTypeResolver.toType(String::class)
        private val umn = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer)
        private fun LogRecord.roundTrip(): LogRecord? =
            LogRecord.fromByteArray(toByteArray(JavaDocumentSerializer), JavaDocumentSerializer, AutoTypeResolver)

        @Test fun `serialization round-trip`() {
            val id = UUID.randomUUID()
            listOf(LogRecord.DocumentCreated("kvak", stringType, id), LogRecord.DocumentUpdated("foo", stringType, id),
                LogRecord.DocumentDeleted(id)).forEach { record ->
                expect(record) { record.roundTrip() }
            }
            expect(null) { LogRecord.fromByteArray(null, JavaDocumentSerializer, AutoTypeResolver) }
        }

        @Test fun `DocumentCreated2 deserializes as DocumentCreated`() {
            val id = umn.create("kvak")
            val record = LogRecord.DocumentCreated2(umn.findSerialized(id)!!, id)
            expect(LogRecord.DocumentCreated("kvak", stringType, id)) { record.roundTrip() }
            assertFailsWith<IllegalStateException> { record.applyTo(umn) }
        }

        @Test fun `unknown record type fails to deserialize`() {
            val bytes = serializeData { writeByte(25); writeLong(0L); writeLong(0L) }
            assertFailsWith<RuntimeException> { LogRecord.fromByteArray(bytes, JavaDocumentSerializer, AutoTypeResolver) }
        }

        @Test fun `applying records`() {
            val id = UUID.randomUUID()
            LogRecord.DocumentCreated("kvak", stringType, id).applyTo(umn)
            expect("kvak") { umn.get(id, String::class) }
            LogRecord.DocumentUpdated("foo", stringType, id).applyTo(umn)
            expect("foo") { umn.get(id, String::class) }
            LogRecord.DocumentDeleted(id).applyTo(umn)
            expect(false) { umn.exists(id) }
            // an update of a deleted document must not resurrect it
            LogRecord.DocumentUpdated("bar", stringType, id).applyTo(umn)
            expect(false) { umn.exists(id) }
        }
    }
}
