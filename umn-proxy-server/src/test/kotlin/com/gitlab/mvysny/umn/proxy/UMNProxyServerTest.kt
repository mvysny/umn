package com.gitlab.mvysny.umn.proxy

import com.github.mvysny.dynatest.DynaTest
import com.gitlab.mvysny.umn.core.JavaDocumentSerializer
import com.gitlab.mvysny.umn.core.LogRecord
import com.gitlab.mvysny.umn.proxyclient.ProxyClient
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.tester.AutoTypeResolver
import com.gitlab.mvysny.umn.sync.tester.addAsync
import com.gitlab.mvysny.umn.sync.tester.expectAsync
import com.gitlab.mvysny.umn.sync.tester.globalLogClientTestBattery
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.util.*
import kotlin.test.expect

class UMNProxyServerTest : DynaTest({
    beforeEach {
        val dbDir = createTempDir().absoluteFile
        val lp = MVStoreGlobalLogProvider(dbDir)
        lp.mvstorelog.createOrUpdateUser("user", "example")
        server = UMNProxyServer(InetSocketAddress.createUnresolved("127.0.0.1", 51343), lp)
        server.start()
    }
    afterEach { server.close() }

    globalLogClientTestBattery {
        createSyncClient()
    }

    group("sync writes logs into the database") {
        lateinit var client: GlobalLogClient
        beforeEach { client = createSyncClient() }
        afterEach { client.close() }

        test("publishing item writes it into the mvstorelog") {
            val item = LogRecord.DocumentCreated("foo", AutoTypeResolver.toType(String::class), UUID.randomUUID())
            var addCallbackCalled = false
            client.setAddCallback { addCallbackCalled = true }
            var receiverCallbackCalled = false
            client.setReceiverCallback(0L) { record->
                receiverCallbackCalled = true
                expect(item) { LogRecord.fromByteArray(record, JavaDocumentSerializer, AutoTypeResolver) }
            }
            client.start()
            client.addAsync(item)
            expectAsync(true) { addCallbackCalled }
            expectAsync(true) { receiverCallbackCalled }
            expect(mapOf("user" to 1L)) { AdminServer.mvstoreGlobalLog.getLogSizes() }
        }
    }
})

fun createSyncClient(): ProxyClient {
    val client = OkHttpClient()
    val request = Request.Builder().url("ws://localhost:51343")
    return ProxyClient(client, request, "user", "example")
}
