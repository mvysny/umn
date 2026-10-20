package com.gitlab.mvysny.umn.sync.tester

import com.gitlab.mvysny.umn.core.AutoTypeResolver
import com.gitlab.mvysny.umn.core.JavaDocumentSerializer
import com.gitlab.mvysny.umn.core.LogRecord
import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.expect

/**
 * Tests a [GlobalLogClient]; run it as a nested class of the client's own test:
 *
 * ```
 * @Nested inner class Battery : GlobalLogClientTestBattery() {
 *     override fun newClient(): GlobalLogClient = log.newClient()
 * }
 * ```
 *
 * ## Implementation notes
 * Only the battery's nested groups call [newClient], from their `@BeforeEach`, which runs after
 * every enclosing class's `@BeforeEach`: create the global log there.
 */
abstract class GlobalLogClientTestBattery {
    /**
     * Expected to return clients connected to the same global log; the global log
     * must be cleared between individual tests.
     */
    abstract fun newClient(): GlobalLogClient

    // tests whether the client correctly responds with IllegalStateExceptions
    @Nested
    inner class ContractTests {
        private lateinit var client: GlobalLogClient
        @BeforeEach fun createClient() { client = newClient() }
        @AfterEach fun closeClient() { client.close() }

        @Test fun `client can be closed immediately even if not started`() {
            client.close()
        }
        @Test fun `client can be closed multiple times`() {
            client.close()
            client.close()
        }
        @Test fun `calling addAsync() on unstarted client fails`() {
            assertFailsWith<IllegalStateException> {
                client.addLogCreated("foo")
            }
        }
        @Test fun `calling addAsync() on closed client fails`() {
            client.close()
            assertFailsWith<IllegalStateException> {
                client.addLogCreated("foo")
            }
        }
        @Test fun `calling addAsync() on started+closed client fails`() {
            client.start()
            client.close()
            assertFailsWith<IllegalStateException> {
                client.addLogCreated("foo")
            }
        }
        @Test fun `calling start() multiple times fails`() {
            client.start()
            assertFailsWith<IllegalStateException> {
                client.start()
            }
        }
        @Test fun `calling setReceiverCallback() with negative rewind fails`() {
            val ex = assertFailsWith<IllegalArgumentException> {
                client.setReceiverCallback(-25L) {}
            }
            assertContains(ex.message!!, "rewind")
        }
        @Test fun `calling setReceiverCallback() on started client fails`() {
            client.start()
            assertFailsWith<IllegalStateException> {
                client.setReceiverCallback(0L) {}
            }
        }
        @Test fun `calling setAddCallback() on started client fails`() {
            client.start()
            assertFailsWith<IllegalStateException> {
                client.setAddCallback {}
            }
        }
    }

    @Nested
    inner class SimpleListenerTests {
        private lateinit var client: GlobalLogClient
        @BeforeEach fun createClient() { client = newClient() }
        @AfterEach fun closeClient() { client.close() }

        @Test fun `publishing item calls both listeners`() {
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

    @Nested
    inner class TwoClientTest : MultipleClientTests(2)

    @Nested
    inner class TenClientTest : MultipleClientTests(10)

    /**
     * Runs tests on multitude of clients.
     * @param clientCount must be 2 or higher
     */
    abstract inner class MultipleClientTests(private val clientCount: Int) {
        init {
            require(clientCount >= 2) { "$clientCount must be 2 or higher" }
        }
        private lateinit var clients: List<GlobalLogClient>
        @BeforeEach fun createClients() { clients = List(clientCount) { newClient() } }
        @AfterEach fun closeClients() { clients.forEach { it.closeQuietly() } }

        @Test fun `addAsync() broadcasts one message to all other clients`() {
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

        @Test fun `addAsync() preserves message order`() {
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
