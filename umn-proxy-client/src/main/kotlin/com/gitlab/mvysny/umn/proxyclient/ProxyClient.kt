package com.gitlab.mvysny.umn.proxyclient

import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.InvalidUsernamePasswordException
import com.gitlab.mvysny.umn.sync.SerializedLogRecord
import okhttp3.*
import okio.ByteString
import org.slf4j.LoggerFactory
import java.lang.RuntimeException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @property client the client which will be used to WS-connect to the server.
 * @param requestBuilder the request builder with [Request.Builder.url] set.
 */
class ProxyClient(val client: OkHttpClient, val requestBuilder: Request.Builder, val username: String, private val password: String) : GlobalLogClient {
    private val listener = Listener()

    inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // do nothing. We either await for socket close (on username/password mismatch or for some other reason), or
            // we expect a surge of messages to arrive
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            webSocket.close(1003, "Encountered unexpected Text Message")
            startFuture.completeExceptionally(RuntimeException("1003: encountered unexpected Text Message"))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val msg = AbstractMessage.fromByteArray(bytes.toByteArray())
            when (msg) {
                is AbstractMessage.OnLogRecord -> {
                    startFuture.complete(Unit)
                    receiveCallback(msg.record)
                }
                is AbstractMessage.OnAdded -> {
                    startFuture.complete(Unit)
                    addCallback()
                }
                AbstractMessage.Started -> startFuture.complete(Unit)
                else -> webSocket.close(1002, "Unexpected message: $msg")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (code == AbstractMessage.WS_INVALID_USERNAME_PASSWORD) {
                startFuture.completeExceptionally(InvalidUsernamePasswordException(reason))
            } else {
                startFuture.completeExceptionally(RuntimeException("$code: $reason"))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log.error("websocket failed: $t", t)
            startFuture.completeExceptionally(RuntimeException(t.message, t))
        }
    }

    private lateinit var ws: WebSocket
    override val isStarted: Boolean
        get() = started.get() && !closed.get()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    internal var addCallback: () -> Unit = {}
    internal var receiveCallback: (record: SerializedLogRecord) -> Unit = {}

    private fun checkNotClosed() {
        check(!closed.get()) { "Already closed" }
    }

    private fun checkNotStarted() {
        check(!started.get()) { "Already started" }
    }

    override fun setReceiverCallback(rewind: Long, callback: (record: SerializedLogRecord) -> Unit) {
        require(rewind >= 0L) { "Parameter rewind: invalid value $rewind - must be 0 or greater" }
        checkNotStarted()
        requestBuilder.addHeader("rewind", rewind.toString())
        receiveCallback = callback
    }

    override fun setAddCallback(callback: () -> Unit) {
        checkNotStarted()
        addCallback = callback
    }

    private val startFuture = CompletableFuture<Unit>()

    override fun start() {
        checkNotClosed()
        check(started.compareAndSet(false, true)) { "Already started" }

        val request = requestBuilder
            .addHeader("username", username)
            .addHeader("password", password).build()
        ws = client.newWebSocket(request, listener)
        try {
            startFuture.get()
        } catch (e: ExecutionException) {
            if (e.cause is InvalidUsernamePasswordException) {
                throw InvalidUsernamePasswordException(e.cause!!.message!!, e)
            }
            throw e
        }

        // if we get here, we were not canceled and there has been no exception. we're started! ;)
    }

    override fun addAsync(record: SerializedLogRecord) {
        check(started.get()) { "Not yet started" }
        checkNotClosed()
        ws.send(AbstractMessage.AddAsync(record))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            ws.close(1000, "CLOSED BY USER")
        }
    }
    
    companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(ProxyClient::class.java)
    }
}

fun WebSocket.send(msg: AbstractMessage) {
    val bytes = msg.asByteArray()
    send(ByteString.of(bytes, 0, bytes.size))
}
