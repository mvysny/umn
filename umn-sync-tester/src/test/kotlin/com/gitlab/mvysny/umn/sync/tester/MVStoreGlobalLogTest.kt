package com.gitlab.mvysny.umn.sync.tester

import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectThrows
import com.gitlab.mvysny.umn.core.JavaDocumentSerializer
import com.gitlab.mvysny.umn.sync.InvalidUsernamePasswordException
import com.gitlab.mvysny.umn.sync.MVStoreGlobalLog
import org.h2.mvstore.MVStore
import java.io.File

class MVStoreGlobalLogTest : DynaTest({
    lateinit var log: MVStoreGlobalLog
    beforeEach {
        val db = MVStore.open(File.createTempFile("tmp", null).absolutePath)
        log = MVStoreGlobalLog(db)
        log.createOrUpdateUser("foo", "bar")
    }
    afterEach {
        log.close()
    }

    globalLogClientTestBattery { log.newClient("foo", "bar") }

    group("test invalid credentials") {
        test("non-existing user") {
            val client = log.newClient("foo25", "bar")
            client.setAddCallback {}
            client.setReceiverCallback(0L) {}
            expectThrows(InvalidUsernamePasswordException::class, "foo") {
                client.start()
            }
        }
        test("invalid password") {
            val client = log.newClient("foo", "bar25")
            client.setAddCallback {}
            client.setReceiverCallback(0L) {}
            expectThrows(InvalidUsernamePasswordException::class, "foo") {
                client.start()
            }
        }
    }
})
