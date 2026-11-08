package com.gitlab.mvysny.umn.sync.tester

import com.gitlab.mvysny.umn.core.AutoTypeResolver
import com.gitlab.mvysny.umn.core.MvstoreUMN
import com.gitlab.mvysny.umn.core.JavaDocumentSerializer
import com.gitlab.mvysny.umn.core.UMN
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.InMemoryGlobalLog
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested

class InMemoryGlobalLogTest {
    private lateinit var log: InMemoryGlobalLog
    @BeforeEach fun createLog() { log = InMemoryGlobalLog() }

    @Nested inner class GlobalLogClientTests : GlobalLogClientTestBattery() {
        override fun newClient(): GlobalLogClient = log.newClient()
    }

    @Nested inner class WriterReaderOnInMemory : WriterReaderTestBattery(3) {
        override fun newStorage(): UMN = MvstoreUMN.inMemory(JavaDocumentSerializer, AutoTypeResolver)
        override fun newClient(): GlobalLogClient = log.newClient()
    }

    @Nested inner class WriterReaderOnMvstore : WriterReaderTestBattery(3) {
        override fun newStorage(): UMN = createMvstoreUMN()
        override fun newClient(): GlobalLogClient = log.newClient()
    }

    @Nested inner class SyncOnInMemory : SyncTestBattery(3) {
        override fun newStorage(): UMN = MvstoreUMN.inMemory(JavaDocumentSerializer, AutoTypeResolver)
        override fun newClient(): GlobalLogClient = log.newClient()
    }

    @Nested inner class SyncOnMvstore : SyncTestBattery(3) {
        override fun newStorage(): UMN = createMvstoreUMN()
        override fun newClient(): GlobalLogClient = log.newClient()
    }
}
