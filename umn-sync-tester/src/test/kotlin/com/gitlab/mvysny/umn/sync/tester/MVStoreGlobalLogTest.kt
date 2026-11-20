package com.gitlab.mvysny.umn.sync.tester

import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.InvalidUsernamePasswordException
import com.gitlab.mvysny.umn.sync.MVStoreGlobalLog
import com.gitlab.mvysny.umn.sync.MVStoreGlobalLogClient
import com.gitlab.mvysny.umn.sync.append
import org.h2.mvstore.MVStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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

    @Test fun `deleteUser() frees the user's log in the file`() {
        // auto-compaction off: it would refill the store while the test measures it
        val db = MVStore.Builder().autoCompactFillRate(0).fileName(File.createTempFile("tmp", null).absolutePath).open()
        db.retentionTime = 0  // young chunks count as wholly live
        MVStoreGlobalLog(db).use { log ->
            val users = listOf("kept", "deleted")
            users.forEach { log.createOrUpdateUser(it, "bar") }
            repeat(600) { i ->
                users.forEach { log.getLogMap(it).append(Random.nextBytes(1000)) }
                if (i % 20 == 0) db.commit()
            }
            db.commit()
            val before = db.chunksFillRate
            log.deleteUser("deleted")
            db.commit()
            // half the data gone: the fill rate halves, give or take the B-tree's own garbage
            assertTrue(db.chunksFillRate < before * 2 / 3, "fill rate $before % → ${db.chunksFillRate} %")
            expect(listOf("kept")) { log.listUsers() }
        }
    }

    @Test fun `deleteUser() closes the user's clients, and only theirs`() {
        log.createOrUpdateUser("other", "bar")
        val deleted = log.newClient("foo", "bar").apply { start() }
        val other = log.newClient("other", "bar").apply { start() }
        log.deleteUser("foo")
        expect(false) { deleted.isStarted }
        assertFailsWith<IllegalStateException> { deleted.addLogCreated("foo") }
        expect(true) { other.isStarted }
        other.addLogCreated("foo")
        other.close()
    }

    @Test fun `deleteUser() fires the user-deleted callback of the user's started clients only`() {
        log.createOrUpdateUser("other", "bar")
        val fired = mutableListOf<String>()
        fun client(user: String, start: Boolean) = (log.newClient(user, "bar") as MVStoreGlobalLogClient).apply {
            setUserDeletedCallback { fired += "$user/started=$start" }
            if (start) start()
        }
        val clients = listOf(client("foo", true), client("foo", false), client("other", true))
        log.deleteUser("foo")
        expect(listOf("foo/started=true")) { fired }
        clients.forEach { it.close() }
    }

    @Test fun `a client replaying the log while its user is deleted stops quietly`() {
        val writer = log.newClient("foo", "bar").apply { start() }
        repeat(3) { writer.addLogCreated("foo") }
        writer.close()

        val received = AtomicInteger()
        val reader = log.newClient("foo", "bar")
        reader.setReceiverCallback(0L) {
            received.incrementAndGet()
            log.deleteUser("foo")  // mid-replay: the next records are gone
        }
        reader.start()
        expect(1) { received.get() }
        expect(false) { reader.isStarted }
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
