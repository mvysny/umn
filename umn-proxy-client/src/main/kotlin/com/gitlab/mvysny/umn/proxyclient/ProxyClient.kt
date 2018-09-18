package com.gitlab.mvysny.umn.proxyclient

import com.gitlab.mvysny.umn.core.LogRecord
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.SerializedLogRecord
import okhttp3.*
import okio.ByteString
import java.lang.IllegalStateException
import java.util.concurrent.CompletableFuture

/**
 * @property client the client which will be used to WS-connect to the server.
 * @param requestBuilder the request builder with [Request.Builder.url] set.
 */
class ProxyClient(val client: OkHttpClient, requestBuilder: Request.Builder, username: String, password: String) : GlobalLogClient {
    val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            throw IllegalStateException("Shouldn't be called - the communication is entirely binary")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        }
    }

    private val ws: WebSocket

    init {
        val request = requestBuilder.addHeader("username", username).addHeader("password", password).build()
        ws = client.newWebSocket(request, listener)
    }

    override fun setReceiverCallback(rewind: Long, callback: (record: LogRecord) -> Unit) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun setAddCallback(callback: () -> Unit) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun start() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun addAsync(record: SerializedLogRecord) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override val isStarted: Boolean
        get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.

    override fun close() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}
