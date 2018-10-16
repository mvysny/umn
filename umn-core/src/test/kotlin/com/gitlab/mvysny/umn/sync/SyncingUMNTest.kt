package com.gitlab.mvysny.umn.sync

import com.github.mvysny.dynatest.DynaTest
import com.gitlab.mvysny.umn.core.*
import kotlin.concurrent.thread
import kotlin.test.expect

class SyncingUMNTest : DynaTest({
    lateinit var log: InMemoryGlobalLog
    beforeEach { log = InMemoryGlobalLog() }

    group("basic test battery on an SyncingUMN backed by in-memory global log client") {
        genericUmnTestBattery { InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer).syncing({ log.newClient() }, JavaDocumentSerializer, true) }
    }

    group("tests with sync sending delayed acks") {
        lateinit var umn: SyncingUMN
        beforeEach {
            umn = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer).syncing(
                { log.newClient().withDelayedAck() },
                JavaDocumentSerializer,
                true
            )
        }
        afterEach { umn.close() }

        test("syncer spammed messages until ACK was received") {
            umn.create("foo")
            Thread.sleep(200)
            expect(1) { log.log.size }
        }
    }

    group("forceRestartSync-related tests") {
        val tempFile = createTempFile().absolutePath
        lateinit var umn: SyncingUMN
        fun createUMN(forceRestartSync: Boolean): SyncingUMN = createMvstoreUMN(tempFile).syncing(
            { log.newClient() },
            JavaDocumentSerializer,
            true,
            forceRestartSync
        )
        beforeEach { umn = createUMN(false) }
        afterEach { umn.close() }

        test("forceRestartSync will push everything to the server again") {
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
})

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
