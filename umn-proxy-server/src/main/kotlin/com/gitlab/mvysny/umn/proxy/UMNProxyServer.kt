package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.proxyclient.AbstractMessage
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.InvalidUsernamePasswordException
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.websocket.api.RemoteEndpoint
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.WebSocketListener
import org.eclipse.jetty.websocket.server.WebSocketHandler
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * The web socket listener.
 */
class UMNProxyWebsocketListener : WebSocketListener {
    private lateinit var session: Session
    private var username: String? = null
    /**
     * Null until the connection's headers have been verified.
     */
    private var logStorage: GlobalLogClient? = null
    override fun onWebSocketClose(statusCode: Int, reason: String?) {
        log.info("$username: Closing web socket: statusCode=$statusCode, reason=$reason")
        logStorage?.close()
    }

    override fun onWebSocketError(t: Throwable) {
        log.error("$username: Web socket failure: $t", t)
        logStorage?.close()
    }

    override fun onWebSocketConnect(session: Session): Unit {
        try {
            log.info("Got connection; headers=${session.upgradeRequest.headers}")
            this.session = session

            val username: String? = session.upgradeRequest.getHeader("username")
            val password: String? = session.upgradeRequest.getHeader("password")
            val rewindStr: String? = session.upgradeRequest.getHeader("rewind")
            if (username == null || password == null || rewindStr == null) {
                throw InvalidUsernamePasswordException("Missing headers")
            }
            this.username = username
            val logStorage = server.globalLogClientProvider.createClient(username, password)
            this.logStorage = logStorage

            val rewind = rewindStr.toLong()
            logStorage.setAddCallback {
                session.remote.send(AbstractMessage.OnAdded)
            }
            logStorage.setReceiverCallback(rewind) { record ->
                session.remote.send(AbstractMessage.OnLogRecord(record))
            }
            logStorage.start()
            session.remote.send(AbstractMessage.Started)
            log.info("Connection successful, sent handshake AbstractMessage.Started")
        } catch (e: InvalidUsernamePasswordException) {
            logStorage?.closeQuietly()
            log.warn("Invalid username/password", e)
            session.close(AbstractMessage.WS_INVALID_USERNAME_PASSWORD, e.message)
        } catch (e: Throwable) {
            logStorage?.closeQuietly()
            log.warn("General storage error", e)
            session.close(AbstractMessage.WS_SERVER_ERROR, e.message)
        }
    }

    override fun onWebSocketText(message: String) {
        log.error("Unexpected: Received text message $message, MITM?!?")
        session.close(1003, "UNEXPECTED TEXT MESSAGE")
        logStorage?.close()
    }

    override fun onWebSocketBinary(payload: ByteArray, offset: Int, len: Int) {
        val msg = AbstractMessage.fromByteArray(payload, offset, len)
        log.debug("Got message $msg")
        if (msg is AbstractMessage.AddAsync) {
            logStorage!!.addAsync(msg.record)
        } else {
            session.close(1002, "Unexpected message arrived: $msg")
            logStorage?.close()
        }
    }

    companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(UMNProxyWebsocketListener::class.java)
    }
}

/**
 * The singleton server instance.
 */
lateinit var server: UMNProxyServer

/**
 * The UMN Proxy server itself. Call [start] to listen for websocket queries. Call [close] to stop the server.
 * @param adr listen on this socket address
 * @param secure optionally also listen on a secure socket address
 * @param globalLogClientProvider [UMNProxyWebsocketListener] uses this guy to delegate the global log calls to.
 */
class UMNProxyServer(adr: InetSocketAddress, secure: Secure?, val globalLogClientProvider: GlobalLogClientProvider) : Closeable {
    private val server = Server(adr)
    init {
        server.handler = WebSocketHandler.Simple(UMNProxyWebsocketListener::class.java)
        secure?.registerJettyConnector(server)
    }

    /**
     * Starts the server asynchronously.
     */
    fun start() {
        server.start()
    }

    override fun close() {
        server.stop()
        globalLogClientProvider.close()
    }
}

fun RemoteEndpoint.send(msg: AbstractMessage) {
    val bytes = msg.asByteArray()
    check(bytes.isNotEmpty()) { "sending empty message!" }
    sendBytes(ByteBuffer.wrap(bytes))
}
