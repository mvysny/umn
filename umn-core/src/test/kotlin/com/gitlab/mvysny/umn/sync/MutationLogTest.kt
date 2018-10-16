package com.gitlab.mvysny.umn.sync

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectList
import com.gitlab.mvysny.umn.core.*

class MutationLogTest : DynaTest({
    group("in-memory UMN") {
        mutationLogTestBattery { InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer) }
    }
    group("MVStore UMN") {
        mutationLogTestBattery { createMvstoreUMN() }
    }
})

fun DynaNodeGroup.mutationLogTestBattery(storageProducer: ()->UMN) {
    lateinit var storage: UMN
    beforeEach { storage = storageProducer() }
    afterEach { storage.close() }
    group("initial population") {
        test("initial population ignores deleted documents") {
            storage.delete(storage.create("kvak"))
            val log = storage.mutationLog(JavaDocumentSerializer).second
            expectList() { log.drain() }
        }
        test("drain cleans the log") {
            val id = storage.create("kvak")
            storage.update(id, "foo")
            val log = storage.mutationLog(JavaDocumentSerializer).second
            expectList(LogRecord.DocumentCreated("foo", AutoTypeResolver.toType(String::class), id)) { log.drain() }
            expectList() { log.drain() }
        }
        test("initial population does not pick document updates") {
            val id = storage.create("kvak")
            storage.update(id, "foo")
            val log = storage.mutationLog(JavaDocumentSerializer).second
            expectList(LogRecord.DocumentCreated("foo", AutoTypeResolver.toType(String::class), id)) { log.drain() }
        }
        test("initial population runs only once") {
            val id = storage.create("kvak")
            var log = storage.mutationLog(JavaDocumentSerializer).second
            expectList(LogRecord.DocumentCreated("kvak", AutoTypeResolver.toType(String::class), id)) { log.drain() }
            log = storage.mutationLog(JavaDocumentSerializer).second
            expectList() { log.drain() }
        }
        test("repopulation causes log to be populated two times") {
            val id = storage.create("kvak")
            var log = storage.mutationLog(JavaDocumentSerializer).second
            expectList(LogRecord.DocumentCreated("kvak", AutoTypeResolver.toType(String::class), id)) { log.drain() }
            log = storage.mutationLog(JavaDocumentSerializer, true).second
            expectList(LogRecord.DocumentCreated("kvak", AutoTypeResolver.toType(String::class), id)) { log.drain() }
        }
    }
}