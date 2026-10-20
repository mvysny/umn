package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread
import kotlin.test.expect
import java.io.File

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
