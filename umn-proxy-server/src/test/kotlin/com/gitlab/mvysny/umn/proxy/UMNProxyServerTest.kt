package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.core.AutoTypeResolver
import com.gitlab.mvysny.umn.core.JavaDocumentSerializer
import com.gitlab.mvysny.umn.core.LogRecord
import com.gitlab.mvysny.umn.proxyclient.ProxyClient
import com.gitlab.mvysny.umn.proxyclient.destroy
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.tester.addAsync
import com.gitlab.mvysny.umn.sync.tester.expectAsync
import com.gitlab.mvysny.umn.sync.tester.GlobalLogClientTestBattery
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.test.expect
import java.nio.file.Files

class UMNProxyServerTest {
    companion object {
        private lateinit var httpClient: OkHttpClient
        @BeforeAll @JvmStatic fun createHttpClient() {
            httpClient = OkHttpClient.Builder().pingInterval(1, TimeUnit.MINUTES).build()
        }
        @AfterAll @JvmStatic fun destroyHttpClient() { httpClient.destroy() }
    }

    private lateinit var server: UMNProxyServer
    private lateinit var lp: MVStoreGlobalLogProvider
    @BeforeEach fun startServer() {
        val dbDir = Files.createTempDirectory("tmp").toFile().absoluteFile
        lp = MVStoreGlobalLogProvider(dbDir)
        lp.mvstorelog.createOrUpdateUser("user", "example")
        server = UMNProxyServer(InetSocketAddress.createUnresolved("127.0.0.1", 51343), null, lp)
        server.start()
    }
    @AfterEach fun stopServer() { server.close() }

    @Nested inner class GlobalLogClientTests : GlobalLogClientTestBattery() {
        override fun newClient(): GlobalLogClient = createSyncClient(httpClient)
    }

    @Test fun `when no clients are connected the active client count is zero`() {
        expect(0) { server.globalLogClientProvider.activeClientCount }
    }

    @Test fun `clients connect on any path`() {
        val client = ProxyClient(httpClient, Request.Builder().url("ws://localhost:51343/some/path"), "user", "example")
        client.start()
        client.close()
    }

    @Nested inner class SyncWritesLogsIntoTheDatabase {
        private lateinit var client: GlobalLogClient
        @BeforeEach fun createClient() { client = createSyncClient(httpClient) }
        @AfterEach fun closeClient() { client.close() }

        @Test fun `closing websocket client also closes mvstorelog's client`() {
            client.start()
            Thread.sleep(200)
            expect(1) { server.globalLogClientProvider.activeClientCount }
            client.close()
            Thread.sleep(200)
            expect(0) { server.globalLogClientProvider.activeClientCount }
        }

        @Test fun `publishing item writes it into the mvstorelog`() {
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
            expect(mapOf("user" to 1L)) { lp.mvstorelog.getLogSizes() }
        }
    }
}

fun createSyncClient(httpClient: OkHttpClient): ProxyClient {
    val request = Request.Builder().url("ws://localhost:51343")
    return ProxyClient(httpClient, request, "user", "example")
}
