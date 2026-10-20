package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.proxyclient.AbstractMessage
import com.gitlab.mvysny.umn.proxyclient.ProxyClient
import com.gitlab.mvysny.umn.proxyclient.destroy
import com.gitlab.mvysny.umn.proxyclient.send
import com.gitlab.mvysny.umn.sync.InvalidUsernamePasswordException
import com.gitlab.mvysny.umn.sync.tester.expectAsync
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import io.javalin.Javalin
import io.javalin.websocket.WsConnectContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.expect

/**
 * Tests how [UMNProxyServer] and [ProxyClient] react to a peer which breaks the protocol described at [AbstractMessage].
 */
class ProtocolViolationTest {
    companion object {
        private lateinit var httpClient: OkHttpClient
        @BeforeAll @JvmStatic fun createHttpClient() { httpClient = OkHttpClient() }
        @AfterAll @JvmStatic fun destroyHttpClient() { httpClient.destroy() }
    }

    /**
     * Connects to [url] with raw [headers], bypassing [ProxyClient]; [closeCode] completes with the code the server closes with.
     */
    private class RawClient(url: String, headers: Map<String, String>) : WebSocketListener() {
        val closeCode = CompletableFuture<Int>()
        val ws: WebSocket = httpClient.newWebSocket(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build(), this)
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCode.complete(code)
            webSocket.close(1000, null)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            closeCode.completeExceptionally(t)
        }
    }

    @Nested inner class MisbehavingClient {
        private lateinit var server: UMNProxyServer
        @BeforeEach fun startServer() {
            val lp = MVStoreGlobalLogProvider(Files.createTempDirectory("tmp").toFile().absoluteFile)
            lp.mvstorelog.createOrUpdateUser("user", "example")
            server = UMNProxyServer(InetSocketAddress.createUnresolved("127.0.0.1", 51343), null, lp)
            server.start()
        }
        @AfterEach fun stopServer() { server.close() }

        private val validHeaders = mapOf("username" to "user", "password" to "example", "rewind" to "0")
        private fun connect(headers: Map<String, String> = validHeaders) = RawClient("ws://localhost:51343", headers)

        @Test fun `invalid password fails the client start`() {
            val client = ProxyClient(httpClient, Request.Builder().url("ws://localhost:51343"), "user", "wrong")
            assertFailsWith<InvalidUsernamePasswordException> { client.start() }
            client.close()
        }

        @Test fun `missing headers close the connection as invalid username or password`() {
            expect(AbstractMessage.WS_INVALID_USERNAME_PASSWORD) { connect(mapOf()).closeCode.get(5, TimeUnit.SECONDS) }
        }

        @Test fun `malformed rewind closes the connection as a server error`() {
            expect(AbstractMessage.WS_SERVER_ERROR) { connect(validHeaders + ("rewind" to "foo")).closeCode.get(5, TimeUnit.SECONDS) }
        }

        @Test fun `text message closes the connection`() {
            val client = connect()
            client.ws.send("hello")
            expect(1003) { client.closeCode.get(5, TimeUnit.SECONDS) }
        }

        @Test fun `server-to-client message closes the connection`() {
            val client = connect()
            client.ws.send(AbstractMessage.OnAdded)
            expect(1002) { client.closeCode.get(5, TimeUnit.SECONDS) }
        }
    }

    @Nested inner class MisbehavingServer {
        private var javalin: Javalin? = null
        private fun startServer(onClose: (Int) -> Unit = {}, onConnect: (WsConnectContext) -> Unit) {
            javalin = Javalin.create { config ->
                config.startup.showJavalinBanner = false
                config.routes.ws("/") { ws ->
                    ws.onConnect(onConnect)
                    ws.onClose { ctx -> onClose(ctx.status()) }
                }
            }.start("127.0.0.1", 51343)
        }
        @AfterEach fun stopServer() { javalin?.stop() }
        private fun newClient() = ProxyClient(httpClient, Request.Builder().url("ws://localhost:51343"), "user", "example")

        @Test fun `no server fails the client start with a ConnectException`() {
            val client = newClient()
            assertFailsWith<ConnectException> { client.start() }
            client.close()
        }

        @Test fun `text message fails the client start`() {
            startServer { it.send("hello") }
            val client = newClient()
            assertFailsWith<ExecutionException> { client.start() }
            client.close()
        }

        @Test fun `server error fails the client start with an IOException`() {
            startServer { it.closeSession(AbstractMessage.WS_SERVER_ERROR, "simulated") }
            val client = newClient()
            val ex = assertFailsWith<IOException> { client.start() }
            expect("${AbstractMessage.WS_SERVER_ERROR}: simulated") { ex.message }
            client.close()
        }

        @Test fun `client-to-server message makes the client close the connection`() {
            val closeCode = CompletableFuture<Int>()
            startServer(onClose = { closeCode.complete(it) }) { ctx ->
                ctx.session.send(AbstractMessage.Started)
                ctx.session.send(AbstractMessage.AddAsync(byteArrayOf(1)))
            }
            val client = newClient()
            client.start()
            expect(1002) { closeCode.get(5, TimeUnit.SECONDS) }
            expectAsync(false) { client.isStarted }
            client.close()
        }
    }
}
