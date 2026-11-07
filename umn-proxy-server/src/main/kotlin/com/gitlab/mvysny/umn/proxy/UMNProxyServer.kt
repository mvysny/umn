package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.proxyclient.AbstractMessage
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.InvalidUsernamePasswordException
import io.javalin.Javalin
import io.javalin.websocket.WsConfig
import io.javalin.websocket.WsContext
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.websocket.api.Callback
import org.eclipse.jetty.websocket.api.Session
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.time.Duration

/**
 * One client's web socket connection.
 */
internal class UMNProxyWebsocketListener(private val session: Session, private val globalLogClientProvider: GlobalLogClientProvider) {
    private var username: String? = null
    /**
     * Null until the connection's headers have been verified.
     */
    private var logStorage: GlobalLogClient? = null
    fun onWebSocketClose(statusCode: Int, reason: String?) {
        log.info("$username: Closing web socket: statusCode=$statusCode, reason=$reason")
        logStorage?.close()
    }

    fun onWebSocketError(t: Throwable) {
        log.error("$username: Web socket failure: $t", t)
        logStorage?.close()
    }

    fun onWebSocketConnect() {
        try {
            log.info("Got connection; headers=${session.upgradeRequest.headers.filterKeys { !it.equals("password", ignoreCase = true) }}")

            // Jetty's lookup ignores the header name's case, Javalin's WsContext.header() doesn't
            val username: String? = session.upgradeRequest.getHeader("username")
            val password: String? = session.upgradeRequest.getHeader("password")
            val rewindStr: String? = session.upgradeRequest.getHeader("rewind")
            if (username == null || password == null || rewindStr == null) {
                throw InvalidUsernamePasswordException("Missing headers")
            }
            this.username = username
            // the device then fails as on a wrong password, and its reconnects fail the same way
            val logStorage = globalLogClientProvider.createClient(username, password) {
                log.info("$username: user deleted, closing the web socket")
                session.close(AbstractMessage.WS_INVALID_USERNAME_PASSWORD, "User $username deleted", Callback.NOOP)
            }
            this.logStorage = logStorage

            val rewind = rewindStr.toLong()
            logStorage.setAddCallback {
                session.send(AbstractMessage.OnAdded)
            }
            logStorage.setReceiverCallback(rewind) { record ->
                session.send(AbstractMessage.OnLogRecord(record))
            }
            logStorage.start()
            session.send(AbstractMessage.Started)
            log.info("Connection successful, sent handshake AbstractMessage.Started")
        } catch (e: InvalidUsernamePasswordException) {
            logStorage?.closeQuietly()
            log.warn("Invalid username/password", e)
            session.close(AbstractMessage.WS_INVALID_USERNAME_PASSWORD, e.message, Callback.NOOP)
        } catch (e: Throwable) {
            logStorage?.closeQuietly()
            log.warn("General storage error", e)
            session.close(AbstractMessage.WS_SERVER_ERROR, e.message, Callback.NOOP)
        }
    }

    fun onWebSocketText(message: String) {
        log.error("Unexpected: Received text message $message, MITM?!?")
        session.close(1003, "UNEXPECTED TEXT MESSAGE", Callback.NOOP)
        logStorage?.close()
    }

    fun onWebSocketBinary(payload: ByteBuffer) {
        val bytes = ByteArray(payload.remaining()).also { payload.get(it) }
        val msg = AbstractMessage.fromByteArray(bytes, 0, bytes.size)
        log.debug("Got message $msg")
        if (msg is AbstractMessage.AddAsync) {
            logStorage!!.addAsync(msg.record)
        } else {
            session.close(1002, "Unexpected message arrived: $msg", Callback.NOOP)
            logStorage?.close()
        }
    }

    companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(UMNProxyWebsocketListener::class.java)
    }
}

/**
 * The UMN Proxy server itself. Call [start] to listen for websocket queries. Call [close] to stop the server.
 * @param adr plain ws listens here.
 * @param secure also serves wss, beside plain ws.
 * @param globalLogClientProvider [UMNProxyWebsocketListener] uses this guy to delegate the global log calls to.
 */
internal class UMNProxyServer(adr: InetSocketAddress, secure: Secure?, val globalLogClientProvider: GlobalLogClientProvider) : Closeable {
    private val javalin = Javalin.create { config ->
        config.startup.showJavalinBanner = false
        // Jetty 9's default, which ProxyClient's ping interval is set against; Jetty 12's is 30 seconds
        config.jetty.modifyWebSocketServletFactory { it.idleTimeout = Duration.ofMinutes(5) }
        // Explicit, not Javalin's default connector: Javalin adds that one only when no other connector is added,
        // so the secure one would silently replace plain ws.
        config.jetty.addConnector { server, httpConfiguration ->
            ServerConnector(server, HttpConnectionFactory(httpConfiguration)).apply {
                host = adr.hostString
                port = adr.port
            }
        }
        if (secure != null) {
            config.jetty.addConnector { server, _ -> secure.createJettyConnector(server) }
        }
        // any path, as 0.15 clients connect to whatever URL they were configured with
        config.routes.ws("/*") { ws -> ws.relayTo(globalLogClientProvider) }
    }

    /**
     * Starts the server asynchronously.
     */
    fun start() {
        javalin.start()
    }

    /**
     * The port plain ws is bound to, once started.
     */
    // Javalin's port() reads the first connector: keep plain ws added before the secure one
    val port: Int get() = javalin.port()

    override fun close() {
        javalin.stop()
        globalLogClientProvider.close()
    }
}

private fun WsConfig.relayTo(globalLogClientProvider: GlobalLogClientProvider) {
    onConnect { ctx ->
        ctx.attribute(LISTENER, UMNProxyWebsocketListener(ctx.session, globalLogClientProvider))
        ctx.listener.onWebSocketConnect()
    }
    onBinaryMessage { ctx -> ctx.listener.onWebSocketBinary(ctx.data()) }
    onMessage { ctx -> ctx.listener.onWebSocketText(ctx.message()) }
    onClose { ctx -> ctx.listener.onWebSocketClose(ctx.status(), ctx.reason()) }
    onError { ctx -> ctx.listener.onWebSocketError(ctx.error() ?: IllegalStateException("unknown web socket error")) }
}

private const val LISTENER = "umn-listener"
private val WsContext.listener: UMNProxyWebsocketListener get() = attribute(LISTENER)!!

/**
 * Sends [msg], blocking until it's written: a slow client then holds up the log's callbacks rather than piling the
 * records up in Jetty's unbounded send queue.
 */
internal fun Session.send(msg: AbstractMessage) {
    val bytes = msg.asByteArray()
    check(bytes.isNotEmpty()) { "sending empty message!" }
    Callback.Completable.with { sendBinary(ByteBuffer.wrap(bytes), it) }.get()
}
