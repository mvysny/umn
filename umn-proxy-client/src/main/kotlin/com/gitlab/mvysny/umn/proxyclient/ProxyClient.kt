package com.gitlab.mvysny.umn.proxyclient

import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.InvalidUsernamePasswordException
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.RuntimeException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A client that connects to the UMN Proxy server.
 *
 * One client is one connection: once it fails or the server closes it, [isStarted] turns false for
 * good, and [addAsync] throws [IOException]. Close it and take a new client.
 *
 * *WARNING*: [client] is not closed by this client: [OkHttpClient] instances should be reused as stated in the [OkHttpClient]
 * docs. You should probably create one singleton instance and close it when JVM is being killed.
 *
 * @property client the client which will be used to WS-connect to the server. NOT CLOSED!
 * @param requestBuilder the request builder with [Request.Builder.url] set.
 * @property pingIntervalSeconds 0 sends no websocket pings; replaces [client]'s own [OkHttpClient.Builder.pingInterval].
 * Pings keep the server from closing a connection quiet for 5 minutes, and an unanswered one fails a dead connection,
 * so the syncer reconnects.
 * @throws IllegalArgumentException if [pingIntervalSeconds] is negative.
 */
public class ProxyClient @JvmOverloads public constructor(
    public val client: OkHttpClient,
    public val requestBuilder: Request.Builder,
    public val username: String,
    private val password: String,
    public val pingIntervalSeconds: Int = 60,
) : GlobalLogClient {
    private val listener = Listener()

    /**
     * [client] pinging every [pingIntervalSeconds]; shares its connection pool and threads.
     */
    internal val pingingClient: OkHttpClient

    init {
        require(pingIntervalSeconds >= 0) { "Parameter pingIntervalSeconds: invalid value $pingIntervalSeconds - must be 0 or greater" }
        pingingClient = client.newBuilder().pingInterval(pingIntervalSeconds.toLong(), TimeUnit.SECONDS).build()
        requestBuilder.header("username", username)
                .header("password", password)
                .header("rewind", "0")
    }

    public inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // do nothing. We either await for socket close (on username/password mismatch or for some other reason), or
            // we expect a surge of messages to arrive
            log.debug("Connected to ${webSocket.request().url}")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            log.error("Received text message $text which is unexpected, MITM?!?")
            webSocket.close(1003, "Encountered unexpected Text Message")
            fail(RuntimeException("1003: encountered unexpected Text Message"))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // okhttp still delivers messages that were in flight when close() was called
            if (closed.get()) return
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
                else -> {
                    webSocket.close(1002, "Unexpected message: $msg")
                    fail(RuntimeException("1002: unexpected message $msg"))
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            log.debug("Closing, code=$code reason=$reason")
            webSocket.close(1000, null)
            if (code == AbstractMessage.WS_INVALID_USERNAME_PASSWORD) {
                fail(InvalidUsernamePasswordException(reason))
            } else {
                fail(IOException("$code: $reason"))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!closed.get()) {
                log.error("$username: websocket failed: $t", t)
            }
            fail(t)
        }
    }

    private var ws: WebSocket? = null
    override val isStarted: Boolean
        get() = started.get() && !closed.get() && !failed.get()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    /**
     * The connection failed or the server closed it; this client is dead for good.
     */
    private val failed = AtomicBoolean()

    /**
     * Fails a [start] still connecting with [cause], and a started client's [isStarted] turns false.
     */
    private fun fail(cause: Throwable) {
        failed.set(true)
        startFuture.completeExceptionally(cause)
    }
    private var addCallback: () -> Unit = {}
    private var receiveCallback: (record: ByteArray?) -> Unit = {}

    private fun checkNotClosed() {
        check(!closed.get()) { "Already closed" }
    }

    private fun checkNotStarted() {
        check(!started.get()) { "Already started" }
    }

    override fun setReceiverCallback(rewind: Long, callback: (record: ByteArray?) -> Unit) {
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

        val request: Request = requestBuilder.build()
        // never log request.headers(): they carry the password
        log.debug("Connecting to ${request.url} as $username, rewind=${request.header("rewind")}")
        ws = pingingClient.newWebSocket(request, listener)
        try {
            startFuture.get()
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is InvalidUsernamePasswordException -> throw InvalidUsernamePasswordException(cause.message!!, e)
                // retryable, per GlobalLogClient.start()'s contract; keeps a ConnectException a ConnectException
                is IOException -> throw cause
                else -> throw e
            }
        }

        // if we get here, we were not canceled and there has been no exception. we're started! ;)
        log.info("Connected to Proxy Server at ${request.url}")
    }

    override fun addAsync(record: ByteArray) {
        check(started.get()) { "Not yet started" }
        checkNotClosed()
        val bytes = AbstractMessage.AddAsync(record).asByteArray()
        // send() returns false once the websocket is closing or failed
        if (failed.get() || !ws!!.send(bytes.toByteString())) {
            throw IOException("Connection lost")
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            log.info("Closing connection")
            startFuture.cancel(false)
            ws?.close(1000, "CLOSED BY USER")
            ws = null
        }
    }

    public companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(ProxyClient::class.java)
    }
}

public fun WebSocket.send(msg: AbstractMessage) {
    val bytes: ByteArray = msg.asByteArray()
    send(bytes.toByteString())
}

/**
 * Destroys the [OkHttpClient] including the dispatcher, connection pool, everything. WARNING: THIS MAY AFFECT
 * OTHER http clients if they share e.g. dispatcher executor service.
 */
public fun OkHttpClient.destroy() {
    dispatcher.executorService.shutdown()
    connectionPool.evictAll()
    cache?.close()
}
