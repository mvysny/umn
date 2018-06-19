package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.expectThrows
import java.util.*

fun DynaNodeGroup.globalLogClientTestBattery(clientProducer: ()->GlobalLogClient) {

    lateinit var client: GlobalLogClient
    beforeEach { client = clientProducer() }
    afterEach { client.close() }

    // tests whether the client correctly responds with IllegalStateExceptions
    group("contract tests") {
        test("client can be closed immediately even if not started") {
            client.close()
        }
        test("client can be closed multiple times") {
            client.close()
            client.close()
        }
        test("calling addAsync() on unstarted client fails") {
            expectThrows(IllegalStateException::class) {
                client.addAsync(LogRecord.DocumentCreated("foo", UUID.randomUUID()))
            }
        }
        test("calling addAsync() on closed client fails") {
            client.close()
            expectThrows(IllegalStateException::class) {
                client.addAsync(LogRecord.DocumentCreated("foo", UUID.randomUUID()))
            }
        }
        test("calling addAsync() on started+closed client fails") {
            client.start()
            client.close()
            expectThrows(IllegalStateException::class) {
                client.addAsync(LogRecord.DocumentCreated("foo", UUID.randomUUID()))
            }
        }
        test("calling start() multiple times fails") {
            client.start()
            expectThrows(IllegalStateException::class) {
                client.start()
            }
        }
        test("calling setReceiverCallback() on started client fails") {
            client.start()
            expectThrows(IllegalStateException::class) {
                client.setReceiverCallback(0L) { _, _->}
            }
        }
        test("calling setAddCallback() on started client fails") {
            client.start()
            expectThrows(IllegalStateException::class) {
                client.setAddCallback {}
            }
        }
    }
}
