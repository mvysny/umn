package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectList
import org.h2.mvstore.MVStore

fun DynaNodeGroup.replayLogTestBattery(replayLogProducer: ()->ReplayLogUMN) {
    lateinit var umn: ReplayLogUMN
    beforeEach { umn = replayLogProducer() }
    afterEach { umn.close() }

    group("log producer tests") {
        test("initially the log is empty") {
            expectList() { umn.getLog() }
        }
        test("creating document produces one log entry") {
            val id = umn.create("kvak")
            expectList(LogItem.DocumentCreated("kvak", id)) { umn.getLog() }
        }
        test("creating+updating document produces two log entries") {
            val id = umn.create("kvak")
            umn.update(id, "foo", String::class)
            expectList(LogItem.DocumentCreated("kvak", id), LogItem.DocumentUpdated("foo", id)) { umn.getLog() }
        }
        test("creating+deleting document produces two log entries") {
            val id = umn.create("kvak")
            umn.delete(id, String::class)
            expectList(LogItem.DocumentCreated("kvak", id), LogItem.DocumentDeleted(id)) { umn.getLog() }
        }
    }
}

/**
 * Tests the [IndexingUMN] class.
 */
class ReplayLogUMNTest : DynaTest({
    // doesn't test the indexing capabilities per se; it tests whether the IndexingUMN still behaves as a proper UMN storage.
    group("basic tests") {
        group("in-memory") {
            genericUmnTestBattery { ReplayLogUMN(InMemoryUMN(), JavaDocumentSerializer) }
        }
        group("on mvstore") {
            genericUmnTestBattery {
                val mvstore = MvstoreUMN(MVStore.open(createTempFile().absolutePath), JavaDocumentSerializer)
                ReplayLogUMN(mvstore, JavaDocumentSerializer)
            }
        }
    }

    group("replay log specific tests") {
        group("in-memory") {
            replayLogTestBattery { ReplayLogUMN(InMemoryUMN(), JavaDocumentSerializer) }
        }
        group("on mvstore") {
            replayLogTestBattery {
                val mvstore = MvstoreUMN(MVStore.open(createTempFile().absolutePath), JavaDocumentSerializer)
                ReplayLogUMN(mvstore, JavaDocumentSerializer)
            }
        }
    }
})
