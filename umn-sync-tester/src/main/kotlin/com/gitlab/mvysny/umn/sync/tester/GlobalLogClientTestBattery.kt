package com.gitlab.mvysny.umn.sync.tester

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.expectThrows
import com.gitlab.mvysny.umn.core.JavaDocumentSerializer
import com.gitlab.mvysny.umn.core.LogRecord
import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
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
                client.addLogCreated("foo")
            }
        }
        test("calling addAsync() on closed client fails") {
            client.close()
            expectThrows(IllegalStateException::class) {
                client.addLogCreated("foo")
            }
        }
        test("calling addAsync() on started+closed client fails") {
            client.start()
            client.close()
            expectThrows(IllegalStateException::class) {
                client.addLogCreated("foo")
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
                client.setReceiverCallback(-25L) {}
            }
        }
        test("calling setReceiverCallback() on started client fails") {
            client.start()
            expectThrows(IllegalStateException::class) {
                client.setReceiverCallback(0L) {}
            }
        }
        test("calling setAddCallback() on started client fails") {
            client.start()
            expectThrows(IllegalStateException::class) {
                client.setAddCallback {}
            }
        }
        test("uuid is returned and doesn't change over multiple invocations") {
            val uuid = client.getUUID()
            expect(uuid) { client.getUUID() }
        }
    }

    group("simple listener tests") {
        lateinit var client: GlobalLogClient
        beforeEach { client = clientProducer() }
        afterEach { client.close() }

        test("publishing item calls both listeners") {
            val item = LogRecord.DocumentCreated("foo", AutoTypeResolver.toType(String::class), UUID.randomUUID())
            var addCallbackCalled = false
            client.setAddCallback { addCallbackCalled = true }
            var receiverCallbackCalled = false
            client.setReceiverCallback(0L) { record->
                receiverCallbackCalled = true
                expect(item) { LogRecord.fromByteArray(record!!, JavaDocumentSerializer, AutoTypeResolver) }
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
private fun DynaNodeGroup.multipleClientTestBattery(clientCount: Int, clientProducer: ()-> GlobalLogClient) {
    require(clientCount >= 2) { "$clientCount must be 2 or higher" }

    group("$clientCount client test") {
        lateinit var clients: List<GlobalLogClient>
        beforeEach { clients = List(clientCount) { clientProducer() } }
        afterEach { clients.forEach { it.closeQuietly() } }

        test("addAsync() broadcasts one message to all other clients") {
            val item = LogRecord.DocumentCreated("foo", AutoTypeResolver.toType(String::class), UUID.randomUUID())
            val cts = clients.map { ClientTester(0L, it) }
            clients[0].addAsync(item)
            cts.forEach {
                expectAsync(listOf(item)) { it.getReceived() }
            }
            cts.subList(1, clients.size).forEach {
                expect(0) { it.addCallbackCalledCount.get() }
            }
            expect(1) { cts[0].addCallbackCalledCount.get() }
        }

        test("addAsync() preserves message order") {
            val items = (0..100).map {
                LogRecord.DocumentUpdated("foo$it", AutoTypeResolver.toType(String::class), UUID.randomUUID())
            }
            val cts = clients.map { ClientTester(0L, it) }
            for (item in items) {
                clients[0].addAsync(item)
            }
            cts.forEach {
                expectAsync(items) { it.getReceived() }
            }
            cts.subList(1, clients.size).forEach {
                expect(0) { it.addCallbackCalledCount.get() }
            }
            expect(101) { cts[0].addCallbackCalledCount.get() }
        }
    }
}

private class ClientTester(offset: Long, client: GlobalLogClient) {
    val addCallbackCalledCount = AtomicInteger()
    private val received = LinkedBlockingQueue<LogRecord>()
    fun getReceived(): List<LogRecord> = received.toList()
    init {
        client.setAddCallback { addCallbackCalledCount.incrementAndGet() }
        client.setReceiverCallback(offset) { record -> received.add(LogRecord.fromByteArray(record!!, JavaDocumentSerializer, AutoTypeResolver)) }
        client.start()
    }
}
