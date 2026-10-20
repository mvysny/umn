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
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

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
