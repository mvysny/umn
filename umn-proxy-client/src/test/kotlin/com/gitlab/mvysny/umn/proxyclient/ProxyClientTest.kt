package com.gitlab.mvysny.umn.proxyclient

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.expect

class ProxyClientTest {
    private val http = OkHttpClient()

    @AfterEach fun destroy() { http.destroy() }

    private fun proxyClient(client: OkHttpClient = http, pingIntervalSeconds: Int = 60) =
        ProxyClient(client, Request.Builder().url("ws://localhost:10144"), "user", "example", pingIntervalSeconds)

    @Test fun `pings every minute by default`() {
        expect(60_000) { ProxyClient(http, Request.Builder().url("ws://localhost:10144"), "user", "example").pingingClient.pingIntervalMillis }
    }

    @Test fun `0 sends no pings, whatever the app's client sets`() {
        expect(0) { proxyClient(http.newBuilder().pingInterval(1, TimeUnit.MINUTES).build(), 0).pingingClient.pingIntervalMillis }
    }

    @Test fun `shares the app's connection pool and threads`() {
        val pinging = proxyClient().pingingClient
        assertSame(http.connectionPool, pinging.connectionPool)
        assertSame(http.dispatcher, pinging.dispatcher)
    }

    @Test fun `rejects a negative interval`() {
        assertFailsWith<IllegalArgumentException> { proxyClient(pingIntervalSeconds = -1) }
    }
}
