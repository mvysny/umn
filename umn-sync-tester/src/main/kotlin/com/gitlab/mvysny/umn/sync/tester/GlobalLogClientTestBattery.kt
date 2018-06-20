package com.gitlab.mvysny.umn.sync.tester

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.expectThrows
import com.gitlab.mvysny.umn.core.LogRecord
import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.expect

/**
 * Tests a [GlobalLogClient].
 * @param clientProducer expected to return clients connected to the same global log; the global log
 * must be cleared between individual tests.
 */
fun DynaNodeGroup.globalLogClientTestBattery(clientProducer: ()-> GlobalLogClient) {
    // tests whether the client correctly responds with IllegalStateExceptions
    group("contract tests") {
        lateinit var client: GlobalLogClient
        beforeEach { client = clientProducer() }
        afterEach { client.close() }

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
        test("calling setReceiverCallback() with negative rewind fails") {
            expectThrows(IllegalArgumentException::class, "rewind") {
                client.setReceiverCallback(-25L) { _, _->}
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

    group("simple listener tests") {
        lateinit var client: GlobalLogClient
        beforeEach { client = clientProducer() }
        afterEach { client.close() }

        test("publishing item calls both listeners") {
            val item = LogRecord.DocumentCreated("foo", UUID.randomUUID())
            var addCallbackCalled = false
            client.setAddCallback { addCallbackCalled = true }
            var receiverCallbackCalled = false
            client.setReceiverCallback(0L) { record, orderNumber ->
                receiverCallbackCalled = true
                expect(item) { record }
                expect(0L) { orderNumber }
            }
            client.start()
            client.addAsync(item)
            expectAsync(true) { addCallbackCalled }
            expectAsync(true) { receiverCallbackCalled }
        }
    }

    multipleClientTestBattery(2, clientProducer)

    multipleClientTestBattery(10, clientProducer)
}

/**
 * Runs tests on multitude of clients.
 * @param clientCount must be 2 or higher
 */
fun DynaNodeGroup.multipleClientTestBattery(clientCount: Int, clientProducer: ()-> GlobalLogClient) {
    require(clientCount >= 2) { "$clientCount must be 2 or higher" }

    group("$clientCount client test") {
        lateinit var clients: List<GlobalLogClient>
        beforeEach { clients = List(clientCount) { clientProducer() } }
        afterEach { clients.forEach { it.closeQuietly() } }

        test("addAsync() sends to the other client") {
            val item = LogRecord.DocumentCreated("foo", UUID.randomUUID())
            clients[0].start()
            val receiverCallbackCalled = AtomicInteger()
            clients.subList(1, clients.size).forEach {
                it.setAddCallback { kotlin.test.fail("Should not be called") }
                it.setReceiverCallback(0L) { record, orderNumber ->
                    receiverCallbackCalled.incrementAndGet()
                    expect(item) { record }
                    expect(0L) { orderNumber }
                }
                it.start()
            }

            clients[0].addAsync(item)
            expectAsync(clientCount - 1) { receiverCallbackCalled.get() }
        }
    }
}
