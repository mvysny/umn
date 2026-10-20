package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.proxyclient.AbstractMessage
import com.gitlab.mvysny.umn.proxyclient.ProxyClient
import com.gitlab.mvysny.umn.proxyclient.destroy
import com.gitlab.mvysny.umn.proxyclient.send
import com.gitlab.mvysny.umn.sync.InvalidUsernamePasswordException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.server.WebSocketHandler
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
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
        private lateinit var jetty: Server
        private fun startJetty(onConnect: (Session) -> Unit) {
            jetty = Server(InetSocketAddress("127.0.0.1", 51343))
            jetty.handler = WebSocketHandler.Simple(FakeProxyServer::class.java)
            FakeProxyServer.onConnect = onConnect
            jetty.start()
        }
        @AfterEach fun stopJetty() {
            if (::jetty.isInitialized) jetty.stop()
        }
        private fun newClient() = ProxyClient(httpClient, Request.Builder().url("ws://localhost:51343"), "user", "example")

        @Test fun `no server fails the client start`() {
            val client = newClient()
            assertFailsWith<ExecutionException> { client.start() }
            client.close()
        }

        @Test fun `text message fails the client start`() {
            startJetty { it.remote.sendString("hello") }
            val client = newClient()
            assertFailsWith<ExecutionException> { client.start() }
            client.close()
        }

        @Test fun `server error fails the client start`() {
            startJetty { it.close(AbstractMessage.WS_SERVER_ERROR, "simulated") }
            val client = newClient()
            val ex = assertFailsWith<ExecutionException> { client.start() }
            expect("${AbstractMessage.WS_SERVER_ERROR}: simulated") { ex.cause!!.message }
            client.close()
        }

        @Test fun `client-to-server message makes the client close the connection`() {
            val closeCode = CompletableFuture<Int>()
            FakeProxyServer.onClose = { closeCode.complete(it) }
            startJetty { session ->
                session.remote.send(AbstractMessage.Started)
                session.remote.send(AbstractMessage.AddAsync(byteArrayOf(1)))
            }
            val client = newClient()
            client.start()
            expect(1002) { closeCode.get(5, TimeUnit.SECONDS) }
            client.close()
        }
    }
}

/**
 * Jetty instantiates the listener per connection, hence the callbacks live in the companion.
 */
class FakeProxyServer : org.eclipse.jetty.websocket.api.WebSocketAdapter() {
    override fun onWebSocketConnect(sess: Session) {
        super.onWebSocketConnect(sess)
        onConnect(sess)
    }

    override fun onWebSocketClose(statusCode: Int, reason: String?) {
        super.onWebSocketClose(statusCode, reason)
        onClose(statusCode)
    }

    companion object {
        @Volatile var onConnect: (Session) -> Unit = {}
        @Volatile var onClose: (Int) -> Unit = {}
    }
}
