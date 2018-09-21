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
import java.nio.ByteBuffer

class UMNProxyWebsocketListener : WebSocketListener {
    private lateinit var session: Session
    private lateinit var username: String
    private lateinit var logStorage: GlobalLogClient
    override fun onWebSocketClose(statusCode: Int, reason: String) {
        log.info("$username: Closing web socket: statusCode=$statusCode, reason=$reason")
    }

    override fun onWebSocketError(t: Throwable) {
        log.error("$username: Web socket failure: $t", t)
    }

    override fun onWebSocketConnect(session: Session) {
        log.info("Got connection; headers=${session.upgradeRequest.headers}")
        this.session = session
        val username: String? = session.upgradeRequest.getHeader("username")
        val password: String? = session.upgradeRequest.getHeader("password")
        val rewindStr: String? = session.upgradeRequest.getHeader("rewind")
        if (username == null || password == null || rewindStr == null) {
            log.warn("Missing headers")
            session.close(AbstractMessage.WS_INVALID_USERNAME_PASSWORD, "Missing headers")
            return
        }
        this.username = username
        val rewind = rewindStr.toLong()
        logStorage = server.createGlobalLogClient(username, password)
        logStorage.setAddCallback {
            session.remote.send(AbstractMessage.OnAdded)
        }
        logStorage.setReceiverCallback(rewind) { record ->
            session.remote.send(AbstractMessage.OnLogRecord(record))
        }
        try {
            logStorage.start()
        } catch (e: InvalidUsernamePasswordException) {
            logStorage.closeQuietly()
            log.warn("Invalid username/password", e)
            session.close(AbstractMessage.WS_INVALID_USERNAME_PASSWORD, e.message)
            return
        } catch (e: Throwable) {
            logStorage.closeQuietly()
            log.warn("General storage error", e)
            session.close(AbstractMessage.WS_SERVER_ERROR, e.message)
            return
        }
        session.remote.send(AbstractMessage.Started)
        log.info("Connection successful, sent handshake AbstractMessage.Started")
    }

    override fun onWebSocketText(message: String) {
        log.error("Unexpected: Received text message $message, MITM?!?")
        session.close(1003, "UNEXPECTED TEXT MESSAGE")
    }

    override fun onWebSocketBinary(payload: ByteArray, offset: Int, len: Int) {
        val msg = AbstractMessage.fromByteArray(payload, offset, len)
        log.debug("Got message $msg")
        if (msg is AbstractMessage.AddAsync) {
            logStorage.addAsync(msg.record)
        } else {
            session.close(1002, "Unexpected message arrived: $msg")
        }
    }

    companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(UMNProxyWebsocketListener::class.java)
    }
}

lateinit var server: UMNProxyServer

class UMNProxyServer(val port: Int, val globalLogClientProvider: GlobalLogClientProvider) : Closeable {
    private val server = Server(port)
    init {
        server.handler = WebSocketHandler.Simple(UMNProxyWebsocketListener::class.java)
    }

    fun start() {
        server.start()
    }

    fun createGlobalLogClient(username: String, password: String): GlobalLogClient = globalLogClientProvider.createClient(username, password)

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
