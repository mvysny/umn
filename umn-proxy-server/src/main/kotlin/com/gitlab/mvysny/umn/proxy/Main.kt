package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.proxyclient.AbstractMessage
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.InvalidUsernamePasswordException
import com.gitlab.mvysny.umn.sync.MVStoreGlobalLog
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.websocket.api.RemoteEndpoint
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.WebSocketListener
import org.eclipse.jetty.websocket.server.WebSocketHandler
import org.h2.mvstore.MVStore
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.lang.IllegalArgumentException
import java.nio.ByteBuffer
import java.nio.file.Files

fun createGlobalLogClient(username: String, password: String): GlobalLogClient {
    return mvstorelog.newClient(username, password)
}

class UMNProxyWebsocketServer : WebSocketListener {
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
        this.session = session
        val username: String? = session.upgradeRequest.getHeader("username")
        val password: String? = session.upgradeRequest.getHeader("password")
        if (username == null || password == null) {
            log.warn("Missing headers")
            session.close(AbstractMessage.WS_INVALID_USERNAME_PASSWORD, "Missing headers")
            return
        }
        this.username = username
        val rewind = session.upgradeRequest.getHeader("rewind").toLong()
        logStorage = createGlobalLogClient(username, password)
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
    }

    override fun onWebSocketText(message: String) {
        throw IllegalArgumentException("Not expected to receive text, the protocol is purely binary")
    }

    override fun onWebSocketBinary(payload: ByteArray, offset: Int, len: Int) {
        val msg = AbstractMessage.fromByteArray(payload, offset, len)
        if (msg is AbstractMessage.AddAsync) {
            logStorage.addAsync(msg.record)
        } else {
            throw IllegalArgumentException("Unexpected message $msg")
        }
    }

    companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(UMNProxyWebsocketServer::class.java)
    }
}

private lateinit var mvstorelog: MVStoreGlobalLog

fun main(args: Array<String>) {
    val dbDir = File("db").absoluteFile
    Files.createDirectory(dbDir.toPath())
    MVStore.Builder().compress().fileName(File(dbDir, "umn_proxy_log").absolutePath).open().use { db ->
        mvstorelog = MVStoreGlobalLog(db)
        mvstorelog.createOrUpdateUser("user", "example")
        val port = 8080
        val server = Server(port)
        server.handler = WebSocketHandler.Simple(UMNProxyWebsocketServer::class.java)
        server.start()
        try {
            println("Server is running on port $port, press Enter to kill it")
            System.`in`.read()
        } finally {
            server.stop()
        }
    }
}

fun MVStore.asCloseable(): Closeable = Closeable { this@asCloseable.close() }
fun MVStore.use(block: (MVStore)->Unit) {
    asCloseable().use { block(this) }
}

fun RemoteEndpoint.send(msg: AbstractMessage) = sendBytes(ByteBuffer.wrap(msg.asByteArray()))
