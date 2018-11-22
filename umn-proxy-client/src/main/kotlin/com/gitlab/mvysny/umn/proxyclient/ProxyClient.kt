package com.gitlab.mvysny.umn.proxyclient

import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.InvalidUsernamePasswordException
import com.gitlab.mvysny.umn.sync.SerializedLogRecord
import okhttp3.*
import okio.ByteString
import org.slf4j.LoggerFactory
import java.lang.RuntimeException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A client that connects to the UMN Proxy server.
 *
 * *NOTE*: It is a good idea to configure the client to send ping every 1 minute, to prevent the connection from timing out
 * (which happens by default in 5 minutes in Jetty).
 *
 * *WARNING*: [client] is not closed by this client: [OkHttpClient] instances should be reused as stated in the [OkHttpClient]
 * docs. You should probably create one singleton instance and close it when JVM is being killed.
 *
 * @property client the client which will be used to WS-connect to the server. NOT CLOSED!
 * @param requestBuilder the request builder with [Request.Builder.url] set.
 */
class ProxyClient(val client: OkHttpClient, val requestBuilder: Request.Builder, val username: String, private val password: String) : GlobalLogClient {
    private val listener = Listener()

    init {
        requestBuilder.header("rewind", "0")
    }

    inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // do nothing. We either await for socket close (on username/password mismatch or for some other reason), or
            // we expect a surge of messages to arrive
            log.debug("Connected to ${webSocket.request().url()}")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            log.error("Received text message $text which is unexpected, MITM?!?")
            webSocket.close(1003, "Encountered unexpected Text Message")
            startFuture.completeExceptionally(RuntimeException("1003: encountered unexpected Text Message"))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val msg = AbstractMessage.fromByteArray(bytes.toByteArray())
            log.debug("Received message $msg")
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
            log.debug("Closing, code=$code reason=$reason")
            if (code == AbstractMessage.WS_INVALID_USERNAME_PASSWORD) {
                startFuture.completeExceptionally(InvalidUsernamePasswordException(reason))
            } else {
                startFuture.completeExceptionally(RuntimeException("$code: $reason"))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log.error("$username: websocket failed: $t", t)
            startFuture.completeExceptionally(RuntimeException(t.message, t))
        }
    }

    private var ws: WebSocket? = null
    override val isStarted: Boolean
        get() = started.get() && !closed.get()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private var addCallback: () -> Unit = {}
    private var receiveCallback: (record: SerializedLogRecord?) -> Unit = {}

    private fun checkNotClosed() {
        check(!closed.get()) { "Already closed" }
    }

    private fun checkNotStarted() {
        check(!started.get()) { "Already started" }
    }

    override fun setReceiverCallback(rewind: Long, callback: (record: SerializedLogRecord?) -> Unit) {
        require(rewind >= 0L) { "Parameter rewind: invalid value $rewind - must be 0 or greater" }
        checkNotStarted()
        requestBuilder.header("rewind", rewind.toString())
        receiveCallback = callback
    }

    override fun setAddCallback(callback: () -> Unit) {
        checkNotStarted()
        addCallback = callback
    }

    private val startFuture = CompletableFutureCompat<Unit>()

    override fun start() {
        checkNotClosed()
        check(started.compareAndSet(false, true)) { "Already started" }

        val request: Request = requestBuilder
            .header("username", username)
            .header("password", password).build()
        log.debug("Sending request $request with headers ${request.headers()}")
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
        log.info("Connected to Proxy Server at ${request.url()}")
    }

    override fun addAsync(record: SerializedLogRecord) {
        check(started.get()) { "Not yet started" }
        checkNotClosed()
        ws!!.send(AbstractMessage.AddAsync(record))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            log.info("Closing connection")
            startFuture.cancel(false)
            ws?.close(1000, "CLOSED BY USER")
            ws = null
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

/**
 * Destroys the [OkHttpClient] including the dispatcher, connection pool, everything. WARNING: THIS MAY AFFECT
 * OTHER http clients if they share e.g. dispatcher executor service.
 */
fun OkHttpClient.destroy() {
    dispatcher().executorService().shutdown()
    connectionPool().evictAll()
    cache()?.close()
}
