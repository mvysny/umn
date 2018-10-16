package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectList
import com.gitlab.mvysny.umn.sync.MutationLog

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
 * A test battery which tests the [AnnouncingUMN], [MutationLog] and the serialization of [LogRecord]s.
 */
internal fun DynaNodeGroup.announcingTestBattery(storageProducer: ()->UMN) {
    group("log producer tests") {
        lateinit var umn: AnnouncingUMN
        lateinit var log: MutationLog
        beforeEach { storageProducer().mutationLog(JavaDocumentSerializer).apply { umn = first; log = second } }
        afterEach { umn.close() }

        test("initially the log is empty") {
            expectList() { log.drain() }
        }
        test("creating document produces one log entry") {
            val id = umn.create("kvak")
            expectList(LogRecord.DocumentCreated("kvak", AutoTypeResolver.toType(String::class), id)) { log.drain() }
        }
        test("creating+updating document produces two log entries") {
            val id = umn.create("kvak")
            umn.update(id, "foo")
            expectList(LogRecord.DocumentCreated("kvak", AutoTypeResolver.toType(String::class), id),
                LogRecord.DocumentUpdated("foo", AutoTypeResolver.toType(String::class), id)) { log.drain() }
        }
        test("creating+deleting document produces two log entries") {
            val id = umn.create("kvak")
            umn.delete(id)
            expectList(LogRecord.DocumentCreated("kvak", AutoTypeResolver.toType(String::class), id), LogRecord.DocumentDeleted(id)) { log.drain() }
        }
        test("draining two times in a row won't return the same items") {
            val id = umn.create("kvak")
            umn.delete(id)
            expectList(LogRecord.DocumentCreated("kvak", AutoTypeResolver.toType(String::class), id), LogRecord.DocumentDeleted(id)) { log.drain() }
            expectList() { log.drain() }
        }
    }
}

/**
 * Tests the [AnnouncingUMN] class. Also tests the serialization of [LogRecord]s.
 */
class AnnouncingUMNTest : DynaTest({
    // doesn't test the indexing capabilities per se; it tests whether the IndexingUMN still behaves as a proper UMN storage.
    group("basic tests") {
        group("in-memory") {
            genericUmnTestBattery { InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer).mutationLog(JavaDocumentSerializer).first }
        }
        group("on mvstore") {
            genericUmnTestBattery { createMvstoreUMN().mutationLog(JavaDocumentSerializer).first }
        }
    }

    group("replay log specific tests") {
        group("in-memory") {
            announcingTestBattery { InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer) }
        }
        group("on mvstore") {
            announcingTestBattery { createMvstoreUMN() }
        }
    }
})
