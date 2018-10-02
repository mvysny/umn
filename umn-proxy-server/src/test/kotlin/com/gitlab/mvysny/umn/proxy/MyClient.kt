package com.gitlab.mvysny.umn.proxy

import okhttp3.*
import okio.ByteString
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.server.WebSocketHandler
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread


private class MyClient : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
        println("WS: Connection opened: $response")
        webSocket.send("Hello World!")
        webSocket.send(ByteString.decodeHex("deadbeef"))
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        println("WS Client: Receiving : $text")
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        println("WS Client: Receiving bytes : " + bytes.hex())
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(NORMAL_CLOSURE_STATUS, null)
        println("WS Client: Closing : $code / $reason")
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        println("WS Client: Error: $t")
        t.printStackTrace()
    }

    companion object {
        private val NORMAL_CLOSURE_STATUS = 1000
    }
}

class EchoServer : org.eclipse.jetty.websocket.api.WebSocketListener {
    private lateinit var s: Session
    override fun onWebSocketError(cause: Throwable) {
        cause.printStackTrace()
    }

    override fun onWebSocketClose(statusCode: Int, reason: String?) {
        println("Echo server closing: $statusCode $reason")
    }

    override fun onWebSocketConnect(session: Session) {
        println("Echo server session opened: $session")
        s = session
        s.idleTimeout = 1000L
    }

    override fun onWebSocketText(message: String) {
        println("Server got message $message")
        s.remote.sendString(message)
    }

    override fun onWebSocketBinary(payload: ByteArray, offset: Int, len: Int) {
        println("Server got message ${payload.sliceArray(offset..(offset + len)).joinToString { it.toString(16) }}")
    }
}

fun client() {
    Thread.sleep(1000)
    println("Creating client & connecting")
    val request = Request.Builder().url("ws://localhost:8080")
        .addHeader("username", "johnny1")
        .build()
    val listener = MyClient()
    val client = OkHttpClient.Builder()
        .pingInterval(500, TimeUnit.MILLISECONDS)
        .build()
    val ws = client.newWebSocket(request, listener)
    println("Connected! Awaiting 2s")
    Thread.sleep(2000)
    println("Shutting down client")
    ws.cancel()
    client.close()
}

fun OkHttpClient.close() {
    dispatcher().executorService().shutdown()
}

fun main(args: Array<String>) {
    val server = Server(8080)
    server.handler = WebSocketHandler.Simple(EchoServer::class.java)
    server.start()
    println("Server is running, press Enter to kill it")
    thread {
        try {
            client()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
    server.join()
    System.`in`.read()
}
