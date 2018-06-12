package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectList

/**
 * Thread-safe.
 */
internal class MutationLog(private val buffer: FIFOBuffer<ByteArray>, private val serializer: DocumentSerializer) {
    /**
     * Logs this mutation [item].
     */
    fun log(item: LogItem) {
        buffer.append(item.toByteArray(serializer))
    }

    /**
     * Empties this log. Warning! This operation is NOT atomic - if multiple threads call this, items are randomly drained to both
     * threads.
     */
    fun drain(): List<LogItem> =
        generateSequence { buffer.poll() } .map { LogItem.fromByteArray(it, serializer) } .toList()
}

/**
 * Wraps this UMN in a capturing UMN which sends all mutation events to a log. Returns both the new capturing UMN and the log
 * which hosts the mutation events (in the form of [LogItem]s).
 */
internal fun UMN.mutationLog(serializer: DocumentSerializer): Pair<UMN, MutationLog> {
    val log = MutationLog(FIFOBuffer(openUtilityMap("MUTATION_LOG")), serializer)
    val umn = AnnouncingUMN(this, { log.log(it) })
    return umn to log
}

/**
 * A test battery which tests the [AnnouncingUMN], [MutationLog] and the serialization of [LogItem]s.
 */
internal fun DynaNodeGroup.announcingTestBattery(replayLogProducer: ()->Pair<UMN, MutationLog>) {
    lateinit var umn: UMN
    lateinit var log: MutationLog
    beforeEach { replayLogProducer().apply { umn = first; log = second } }
    afterEach { umn.close() }

    group("log producer tests") {
        test("initially the log is empty") {
            expectList() { log.drain() }
        }
        test("creating document produces one log entry") {
            val id = umn.create("kvak")
            expectList(LogItem.DocumentCreated("kvak", id)) { log.drain() }
        }
        test("creating+updating document produces two log entries") {
            val id = umn.create("kvak")
            umn.update(id, "foo", String::class)
            expectList(LogItem.DocumentCreated("kvak", id), LogItem.DocumentUpdated("foo", id)) { log.drain() }
        }
        test("creating+deleting document produces two log entries") {
            val id = umn.create("kvak")
            umn.delete(id, String::class)
            expectList(LogItem.DocumentCreated("kvak", id), LogItem.DocumentDeleted(id)) { log.drain() }
        }
    }
}

/**
 * Tests the [AnnouncingUMN] class. Also tests the serialization of [LogItem]s.
 */
class AnnouncingUMNTest : DynaTest({
    // doesn't test the indexing capabilities per se; it tests whether the IndexingUMN still behaves as a proper UMN storage.
    group("basic tests") {
        group("in-memory") {
            genericUmnTestBattery { InMemoryUMN().mutationLog(JavaDocumentSerializer).first }
        }
        group("on mvstore") {
            genericUmnTestBattery { createMvstoreUMN().mutationLog(JavaDocumentSerializer).first }
        }
    }

    group("replay log specific tests") {
        group("in-memory") {
            announcingTestBattery { InMemoryUMN().mutationLog(JavaDocumentSerializer) }
        }
        group("on mvstore") {
            announcingTestBattery { createMvstoreUMN().mutationLog(JavaDocumentSerializer) }
        }
    }
})
