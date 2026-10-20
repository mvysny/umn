package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread
import kotlin.test.expect
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class SyncingUMNTest {
    private lateinit var log: InMemoryGlobalLog
    @BeforeEach fun createLog() { log = InMemoryGlobalLog() }

    @Nested inner class GenericOverInMemoryGlobalLog : GenericUmnTestBattery() {
        override fun newUmn(): UMN = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer).syncing({ log.newClient() }, JavaDocumentSerializer, true)
    }

    @Nested inner class TestsWithSyncSendingDelayedAcks {
        private lateinit var umn: SyncingUMN
        @BeforeEach fun createUmn() {
            umn = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer).syncing(
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
            umn = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer).syncing({ newClient().also { client = it } }, JavaDocumentSerializer)
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

        private fun idsInGlobalLog(): Set<UUID> =
            log.log.map { LogRecord.fromByteArray(it, JavaDocumentSerializer, AutoTypeResolver)!!.id }.toSet()

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
 * Every [GlobalLogClient.addAsync] vanishes in transit: never stored, never ACKed.
 */
fun GlobalLogClient.droppingAdds(): GlobalLogClient = ClientDroppingAdds(this)
class ClientDroppingAdds(val delegate: GlobalLogClient) : GlobalLogClient by delegate {
    val dropped = AtomicInteger()
    override fun addAsync(record: SerializedLogRecord) {
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
    lateinit var receiver: (record: SerializedLogRecord?) -> Unit
    override fun setReceiverCallback(rewind: Long, callback: (record: SerializedLogRecord?) -> Unit) {
        receiver = callback
        delegate.setReceiverCallback(rewind, callback)
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
