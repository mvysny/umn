package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.*
import com.gitlab.mvysny.umn.sync.SyncerStatus.*
import org.h2.mvstore.MVStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.expect
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SyncingUMNTest {
    private lateinit var log: InMemoryGlobalLog
    @BeforeEach fun createLog() { log = InMemoryGlobalLog() }

    private fun idsInGlobalLog(): Set<UUID> =
        log.log.map { LogRecord.fromByteArray(it, JavaDocumentSerializer, AutoTypeResolver)!!.id }.toSet()

    @Nested inner class GenericOverInMemoryGlobalLog : GenericUmnTestBattery() {
        override fun newUmn(): UMN = MvstoreUMN.inMemory(JavaDocumentSerializer, AutoTypeResolver).syncing({ log.newClient() }, JavaDocumentSerializer, true)
    }

    @Nested inner class TestsWithSyncSendingDelayedAcks {
        private lateinit var umn: SyncingUMN
        @BeforeEach fun createUmn() {
            umn = MvstoreUMN.inMemory(JavaDocumentSerializer, AutoTypeResolver).syncing(
                { log.newClient().withDelayedAck() },
                JavaDocumentSerializer,
                true
            )
        }
        @AfterEach fun closeUmn() { umn.close() }

        @Test fun `syncer spammed messages until ACK was received`() {
            umn.create("foo")
            Thread.sleep(200)
            expect(1) { log.log.size }
        }
    }

    @Nested inner class ClientSwitch {
        private var newClient: () -> GlobalLogClient = { log.newClient() }
        /**
         * The client the syncer took last.
         */
        @Volatile private var client: GlobalLogClient? = null
        private lateinit var umn: SyncingUMN
        @BeforeEach fun createUmn() {
            umn = MvstoreUMN.inMemory(JavaDocumentSerializer, AutoTypeResolver).syncing({ newClient().also { client = it } }, JavaDocumentSerializer)
        }
        @AfterEach fun closeUmn() { umn.close() }

        private fun start(newClient: () -> GlobalLogClient = { log.newClient() }) {
            this.newClient = newClient
            umn.syncer.start()
            expectAsync(true) { client?.isStarted == true }
        }

        /**
         * Returns once the client is closed, hence its pusher and puller too.
         */
        private fun stop() {
            val client = client!!
            umn.syncer.stop()
            expectAsync(false) { client.isStarted }
        }

        @Test fun `a record lost in transit is re-sent by the next client`() {
            start { log.newClient().droppingAdds() }
            val dropping = client as ClientDroppingAdds
            val lost = umn.create("lost")
            expectAsync(1) { dropping.dropped.get() }
            stop()

            start()
            val delivered = umn.create("delivered")
            expectAsync(setOf(lost, delivered)) { idsInGlobalLog() }
        }

        @Test fun `a late ACK from a closed client acks nothing`() {
            start { ClientWithHeldAcks(log.newClient()) }
            val closedClient = client as ClientWithHeldAcks
            val a = umn.create("a")
            expectAsync(1) { log.log.size }
            stop()

            // a new client re-sends "a" and gets its ACK
            start()
            expectAsync(2) { log.log.size }
            stop()

            // "b" waits in the buffer while the closed client's ACK for "a" arrives
            val b = umn.create("b")
            closedClient.releaseAcks()

            start()
            expectAsync(setOf(a, b)) { idsInGlobalLog() }
        }

        @Test fun `a late record from a closed client is not applied`() {
            start { ClientCapturingReceiver(log.newClient()) }
            val closedClient = client as ClientCapturingReceiver
            stop()

            val ghost = UUID.randomUUID()
            closedClient.receiver(LogRecord.DocumentCreated("ghost", AutoTypeResolver.toType(String::class), ghost).toByteArray(JavaDocumentSerializer))
            expect(false) { umn.exists(ghost) }
        }
    }

    @Nested inner class Reconnect {
        private val clients = CopyOnWriteArrayList<FailingClient>()
        /**
         * Every new client's [GlobalLogClient.start] throws this; null connects.
         */
        @Volatile private var startFailure: Exception? = null
        private lateinit var umn: SyncingUMN
        private fun createUmn(backoff: Backoff) {
            umn = Syncer(MvstoreUMN.inMemory(JavaDocumentSerializer, AutoTypeResolver), JavaDocumentSerializer,
                { FailingClient(log.newClient(), startFailure).also { clients.add(it) } }, false, backoff).syncingUMN
        }
        @BeforeEach fun createUmn() { createUmn(Backoff(10, 50)) }
        @AfterEach fun closeUmn() { umn.close() }

        @Test fun `a failed connection is replaced by a new client`() {
            umn.syncer.start()
            val a = umn.create("a")
            expectAsync(setOf(a)) { idsInGlobalLog() }
            clients.single().fail()

            val b = umn.create("b")
            expectAsync(setOf(a, b)) { idsInGlobalLog() }
            expect(2) { clients.size }
            expect(false) { clients[0].delegate.isStarted }
        }

        @Test fun `a failed start() is retried with a new client`() {
            startFailure = IOException("simulated: server down")
            umn.syncer.start()
            val a = umn.create("a")
            expectAsync(true) { clients.size >= 3 }
            expect(setOf()) { idsInGlobalLog() }

            startFailure = null
            expectAsync(setOf(a)) { idsInGlobalLog() }
        }

        @Test fun `invalid credentials stop the syncer`() {
            startFailure = InvalidUsernamePasswordException("simulated")
            umn.syncer.start()
            expectAsync(1) { clients.size }
            Thread.sleep(200)
            expect(1) { clients.size }
        }

        @Test fun `stop() cuts a back-off short`() {
            umn.close()
            createUmn(Backoff(60_000, 60_000))
            startFailure = IOException("simulated: server down")
            umn.syncer.start()
            expectAsync(1) { clients.size }
            val millis = measureTimeMillis {
                umn.syncer.close()
                umn.syncer.awaitClose()
            }
            assertTrue(millis < 500, "took $millis ms")
        }
    }

    @Nested inner class Status {
        private val clients = CopyOnWriteArrayList<FailingClient>()
        /**
         * Every new client's [GlobalLogClient.start] throws this; null connects.
         */
        @Volatile private var startFailure: Exception? = null
        private val statuses = CopyOnWriteArrayList<SyncerStatus>()
        private lateinit var umn: SyncingUMN
        @BeforeEach fun createUmn() {
            umn = Syncer(MvstoreUMN.inMemory(JavaDocumentSerializer, AutoTypeResolver), JavaDocumentSerializer,
                { FailingClient(log.newClient(), startFailure).also { clients.add(it) } }, false, Backoff(10, 50)).syncingUMN
            umn.syncer.setStatusListener { statuses.add(it) }
        }
        @AfterEach fun closeUmn() { umn.close() }

        @Test fun `a lost connection goes offline and reconnects`() {
            umn.syncer.start()
            expectAsync(listOf(Stopped, Connecting, Connected)) { statuses.toList() }
            clients.single().fail()
            expectAsync(listOf(Stopped, Connecting, Connected, Offline(null, 10), Connecting, Connected)) { statuses.toList() }
            expect(Connected) { umn.syncer.status }
        }

        @Test fun `a failed start() goes offline with its cause, the back-off growing`() {
            val failure = IOException("simulated: server down")
            startFailure = failure
            umn.syncer.start()
            expectAsync(listOf(Stopped, Connecting, Offline(failure, 10), Connecting, Offline(failure, 20))) { statuses.take(5) }
        }

        @Test fun `invalid credentials fail`() {
            val failure = InvalidUsernamePasswordException("simulated")
            startFailure = failure
            umn.syncer.start()
            expectAsync(listOf(Stopped, Connecting, Failed(failure))) { statuses.toList() }
            expect(Failed(failure)) { umn.syncer.status }

            startFailure = null
            umn.syncer.stop()
            umn.syncer.start()
            expectAsync(listOf(Stopped, Connecting, Failed(failure), Stopped, Connecting, Connected)) { statuses.toList() }
        }

        @Test fun `stop() is the last status, not the closing connection's`() {
            umn.syncer.start()
            expectAsync(Connected) { umn.syncer.status }
            umn.syncer.stop()
            expectAsync(Stopped) { umn.syncer.status }
            Thread.sleep(100)
            expect(listOf(Stopped, Connecting, Connected, Stopped)) { statuses.toList() }
        }

        @Test fun `a listener set later hears the current status first`() {
            umn.syncer.start()
            expectAsync(Connected) { umn.syncer.status }
            val late = CopyOnWriteArrayList<SyncerStatus>()
            umn.syncer.setStatusListener { late.add(it) }
            expectAsync(listOf(Connected)) { late.toList() }

            umn.syncer.setStatusListener(null)
            umn.syncer.stop()
            expectAsync(Stopped) { umn.syncer.status }
            expect(listOf(Connected)) { late.toList() }
        }

        @Test fun `a throwing listener doesn't stop syncing`() {
            umn.syncer.setStatusListener { throw RuntimeException("simulated") }
            umn.syncer.start()
            val a = umn.create("a")
            expectAsync(setOf(a)) { idsInGlobalLog() }
        }
    }

    @Nested inner class RecordNotApplied {
        /**
         * While true, indexing "poison" throws: [node] can't apply that record.
         */
        @Volatile private var broken = true
        private val idx = IndexingUMN.IndexDef.simple<String, String>("identity",
            { doc -> if (broken && doc == "poison") throw RuntimeException("simulated") else setOf(doc) }, String::class)
        private val statuses = CopyOnWriteArrayList<SyncerStatus>()
        private lateinit var node: SyncingUMN
        private lateinit var other: SyncingUMN
        @BeforeEach fun createUmns() {
            node = Syncer(IndexingUMN(MvstoreUMN.inMemory(JavaDocumentSerializer, AutoTypeResolver), setOf(idx)), JavaDocumentSerializer,
                { log.newClient() }, false, Backoff(10, 50)).syncingUMN
            node.syncer.setStatusListener { statuses.add(it) }
            node.syncer.start()
            other = MvstoreUMN.inMemory(JavaDocumentSerializer, AutoTypeResolver).syncing({ log.newClient() }, JavaDocumentSerializer, true)
        }
        @AfterEach fun closeUmns() {
            other.close()
            node.close()
        }

        private fun stalls(): List<Offline> = statuses.filterIsInstance<Offline>().filter { it.cause is LogRecordNotAppliedException }

        @Test fun `syncing stalls on the record, backing off, and applies it once fixed`() {
            val poison = other.create("poison")
            val after = other.create("after")
            expectAsync(true) { stalls().size >= 3 }
            expect(listOf(10L, 20L, 40L)) { stalls().take(3).map { it.retryInMillis } }
            expect(0L) { (stalls().first().cause as LogRecordNotAppliedException).logIndex }
            expect(false) { node.exists(poison) }
            expect(false) { node.exists(after) }

            broken = false
            expectAsync(true) { node.exists(poison) && node.exists(after) }
            expect(poison to "poison") { node.facet<IndexingUMN>().getIndex(idx).findFirstDoc(For.Key("poison")) }
        }
    }

    @Test fun `back-off doubles up to the max`() {
        val backoff = Backoff(1000, 60_000)
        expect(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L)) { (1..8).map { backoff.delayMillis(it) } }
        expect(60000L) { backoff.delayMillis(1000) }
    }

    @Nested inner class KillMidPull {
        @TempDir lateinit var dir: File

        @Test fun `a kill never leaves the log pointer past a record the node lacks`() {
            repeat(3) { run ->
                val file = File(dir, "node$run")
                val store = MVStore.Builder().fileName(file.path).autoCommitDisabled().open()
                val node = MvstoreUMN(store, JavaDocumentSerializer, AutoTypeResolver)
                // between ROOT2 and the log pointer: a store spends milliseconds walking them, in which the puller applies
                // records and moves the pointer past them (`R_mvstore_store_per_map`)
                repeat(3000) { node.openUtilityMap<Long, Long>("filler$it") }
                val syncer = Syncer(node, JavaDocumentSerializer, { EndlessLogClient() }, false)
                val pointer = node.openUtilityMap<Boolean, Long>("CURRENT_LOG_PTR")
                syncer.start()
                expectAsync(true) { (pointer.find(false) ?: 0L) > 0L }
                // MVStore's background writer stores, and the node is killed before its next store
                store.commit()
                store.closeImmediately()
                syncer.close()
                syncer.awaitClose()

                createMvstoreUMN(file.path).use { reopened ->
                    val saved = reopened.openUtilityMap<Boolean, Long>("CURRENT_LOG_PTR").find(false)!!
                    val missing = (0 until saved).count { !reopened.exists(UUID(0, it)) }
                    expect(0, "records missing below the saved pointer $saved, run $run") { missing }
                }
            }
        }

        @Test fun `the pointer catches up with an idle log, and is saved on stop`() {
            val other = MvstoreUMN.inMemory(JavaDocumentSerializer, AutoTypeResolver).syncing({ log.newClient() }, JavaDocumentSerializer, true)
            val node = MvstoreUMN.inMemory(JavaDocumentSerializer, AutoTypeResolver)
            val umn = node.syncing({ log.newClient() }, JavaDocumentSerializer, true)
            val pointer = node.openUtilityMap<Boolean, Long>("CURRENT_LOG_PTR")
            try {
                val ids = (1..3).map { other.create("doc $it") }
                expectAsync(true) { ids.all { umn.exists(it) } }
                expectAsync(3L) { pointer.find(false) }

                val last = other.create("last")
                expectAsync(true) { umn.exists(last) }
                umn.syncer.stop()
                expectAsync(Stopped) { umn.syncer.status }
                expect(4L) { pointer.find(false) }
            } finally {
                umn.close()
                other.close()
            }
        }
    }

    @Nested inner class LocalEditCommits {
        @TempDir lateinit var dir: File
        private val file: File get() = File(dir, "node")
        private lateinit var store: MVStore
        private lateinit var umn: SyncingUMN
        @BeforeEach fun createUmn() {
            store = MVStore.Builder().fileName(file.path).autoCommitDisabled().open()
            umn = MvstoreUMN(store, JavaDocumentSerializer, AutoTypeResolver).syncing({ log.newClient() }, JavaDocumentSerializer)
        }
        @AfterEach fun closeUmn() { umn.close() }

        @Test fun `a kill right after a local edit keeps it, and its outgoing record`() {
            val id = umn.create("a")
            store.closeImmediately()
            createMvstoreUMN(file.path).use { reopened ->
                expect("a") { reopened.find(id, String::class) }
                expect(listOf(id)) { MutationLog(reopened, "OUTGOING_BUFFER", JavaDocumentSerializer, false).drain().map { it.id } }
            }
        }

        @Test fun `every local edit commits before it returns`() {
            val id = umn.create("a")
            expect(false) { store.hasUnsavedChanges() }
            umn.update(id, "b")
            expect(false) { store.hasUnsavedChanges() }
            umn.delete(id)
            expect(false) { store.hasUnsavedChanges() }
        }

        @Test fun `a batch commits once, as it returns`() {
            val ids = umn.batch {
                val ids = listOf(umn.create("a"), umn.create("b"))
                expect(true) { store.hasUnsavedChanges() }
                ids
            }
            expect(false) { store.hasUnsavedChanges() }
            expect(listOf("a", "b")) { ids.map { umn.get(it, String::class) } }
        }

        @Test fun `only the outermost batch commits`() {
            umn.batch {
                umn.batch { umn.create("a") }
                expect(true) { store.hasUnsavedChanges() }
            }
            expect(false) { store.hasUnsavedChanges() }
        }

        @Test fun `a batch that throws commits the edits it made`() {
            assertFailsWith<IllegalStateException> {
                umn.batch {
                    umn.create("a")
                    error("simulated")
                }
            }
            expect(false) { store.hasUnsavedChanges() }
            expect(1) { umn.getSequenceOfIDs().count() }
        }

        @Test fun `another thread's edit commits while a batch runs`() {
            umn.batch {
                umn.create("a")
                thread { umn.create("b") }.join()
                expect(false) { store.hasUnsavedChanges() }
            }
        }

        @Test fun `a batch over a UMN that doesn't sync commits as it returns`() {
            val plain = MvstoreUMN(store, JavaDocumentSerializer, AutoTypeResolver)
            plain.batch { plain.create("a") }
            expect(false) { store.hasUnsavedChanges() }
        }
    }

    @Nested inner class ForceRestartSyncRelatedTests {
        private val tempFile = File.createTempFile("tmp", null).absolutePath
        private lateinit var umn: SyncingUMN
        private fun createUMN(forceRestartSync: Boolean): SyncingUMN = createMvstoreUMN(tempFile).syncing(
            { log.newClient() },
            JavaDocumentSerializer,
            true,
            forceRestartSync
        )
        @BeforeEach fun createUmn() { umn = createUMN(false) }
        @AfterEach fun closeUmn() { umn.close() }

        @Test fun `forceRestartSync will push everything to the server again`() {
            umn.create("foo")
            Thread.sleep(200)
            expect(1) { log.log.size }
            umn.close()
            Thread.sleep(200)
            umn = createUMN(true)
            Thread.sleep(200)
            expect(2) { log.log.size }
        }
    }
}

/**
 * [fail] drops the connection: [isStarted] turns false and [addAsync] throws [IOException].
 * @param startFailure [start] throws it instead of connecting.
 */
class FailingClient(val delegate: GlobalLogClient, private val startFailure: Exception? = null) : GlobalLogClient by delegate {
    @Volatile private var failed = false
    fun fail() {
        failed = true
    }
    override val isStarted: Boolean get() = !failed && delegate.isStarted
    override fun start() {
        startFailure?.let { throw it }
        delegate.start()
    }
    override fun addAsync(record: ByteArray) {
        if (failed) throw IOException("simulated: connection lost")
        delegate.addAsync(record)
    }
}

/**
 * Every [GlobalLogClient.addAsync] vanishes in transit: never stored, never ACKed.
 */
fun GlobalLogClient.droppingAdds(): GlobalLogClient = ClientDroppingAdds(this)
class ClientDroppingAdds(val delegate: GlobalLogClient) : GlobalLogClient by delegate {
    val dropped = AtomicInteger()
    override fun addAsync(record: ByteArray) {
        dropped.incrementAndGet()
    }
}

/**
 * Holds back every ACK until [releaseAcks], which fires them even after [close] — as okhttp
 * delivers messages already in flight when a websocket closes.
 */
class ClientWithHeldAcks(val delegate: GlobalLogClient) : GlobalLogClient by delegate {
    private var callback: () -> Unit = {}
    private val heldAcks = AtomicInteger()
    override fun setAddCallback(callback: () -> Unit) {
        this.callback = callback
        delegate.setAddCallback { heldAcks.incrementAndGet() }
    }
    fun releaseAcks() {
        repeat(heldAcks.getAndSet(0)) { callback() }
    }
}

/**
 * Exposes the [receiver] callback, so a test can fire it after [close].
 */
class ClientCapturingReceiver(val delegate: GlobalLogClient) : GlobalLogClient by delegate {
    lateinit var receiver: (record: ByteArray?) -> Unit
    override fun setReceiverCallback(rewind: Long, callback: (record: ByteArray?) -> Unit) {
        receiver = callback
        delegate.setReceiverCallback(rewind, callback)
    }
}

/**
 * A log that never ends: from the rewind on, record `k` creates the document `"k"` under `UUID(0, k)`, as fast as the
 * receiver takes them, on a thread of its own.
 */
class EndlessLogClient : GlobalLogClient {
    @Volatile private var closed = false
    @Volatile private var generator: Thread? = null
    private var rewind = 0L
    private var receiver: (ByteArray?) -> Unit = {}
    override val isStarted: Boolean get() = generator != null && !closed
    override fun setReceiverCallback(rewind: Long, callback: (record: ByteArray?) -> Unit) {
        this.rewind = rewind
        receiver = callback
    }
    override fun setAddCallback(callback: () -> Unit) {}
    override fun start() {
        generator = thread(isDaemon = true, name = "endless log") {
            var k = rewind
            while (!closed) {
                receiver(LogRecord.DocumentCreated("$k", AutoTypeResolver.toType(String::class), UUID(0, k)).toByteArray(JavaDocumentSerializer))
                k++
            }
        }
    }
    override fun addAsync(record: ByteArray) {}
    override fun close() {
        closed = true
        generator?.join()
    }
}

fun GlobalLogClient.withDelayedAck(): GlobalLogClient = ClientWithDelayedAck(this)
class ClientWithDelayedAck(val delegate: GlobalLogClient) : GlobalLogClient by delegate {
    override fun setAddCallback(callback: () -> Unit) {
        delegate.setAddCallback {
            thread {
                Thread.sleep(30)
                callback()
            }
        }
    }
}
