package com.gitlab.mvysny.umn.sync.tester

import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.InvalidUsernamePasswordException
import com.gitlab.mvysny.umn.sync.MVStoreGlobalLog
import org.h2.mvstore.MVStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.expect

class MVStoreGlobalLogTest {
    private lateinit var log: MVStoreGlobalLog
    @BeforeEach fun createLog() {
        val db = MVStore.open(File.createTempFile("tmp", null).absolutePath)
        log = MVStoreGlobalLog(db)
        log.createOrUpdateUser("foo", "bar")
    }
    @AfterEach fun closeLog() {
        log.close()
    }

    @Nested inner class GlobalLogClientTests : GlobalLogClientTestBattery() {
        override fun newClient(): GlobalLogClient = log.newClient("foo", "bar")
    }

    @Test fun `clients of a new user starting at once all write into one log`() {
        repeat(20) { round ->
            val username = "new$round"
            log.createOrUpdateUser(username, "bar")
            val clients = List(8) { log.newClient(username, "bar") }
            val barrier = CyclicBarrier(clients.size)
            clients.map { client ->
                thread {
                    barrier.await()
                    client.start()
                    client.addLogCreated("foo")
                }
            }.forEach { it.join() }
            clients.forEach { it.close() }
            expect(8L, "round $round") { log.getLogMap(username).sizeAsLong() }
        }
    }

    @Test fun `a client whose callback fails keeps the record from no other client`() {
        val failing = log.newClient("foo", "bar")
        failing.setReceiverCallback(0L) { throw RuntimeException("simulated: the session is gone") }
        failing.start()
        val received = AtomicInteger()
        val ok = log.newClient("foo", "bar")
        ok.setReceiverCallback(0L) { received.incrementAndGet() }
        ok.start()
        val writer = log.newClient("foo", "bar")
        writer.start()

        writer.addLogCreated("foo")
        expect(1) { received.get() }
        listOf(failing, ok, writer).forEach { it.close() }
    }

    @Nested inner class InvalidCredentials {
        @Test fun `non-existing user`() {
            val client = log.newClient("foo25", "bar")
            client.setAddCallback {}
            client.setReceiverCallback(0L) {}
            val ex = assertFailsWith<InvalidUsernamePasswordException> {
                client.start()
            }
            assertContains(ex.message!!, "foo")
        }
        @Test fun `invalid password`() {
            val client = log.newClient("foo", "bar25")
            client.setAddCallback {}
            client.setReceiverCallback(0L) {}
            val ex = assertFailsWith<InvalidUsernamePasswordException> {
                client.start()
            }
            assertContains(ex.message!!, "foo")
        }
    }
}
