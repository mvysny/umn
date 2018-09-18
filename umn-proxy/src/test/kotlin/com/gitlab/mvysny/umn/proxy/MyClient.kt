package com.gitlab.mvysny.umn.proxy

import okhttp3.*
import okio.ByteString
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.websocket.server.WebSocketHandler
import kotlin.concurrent.thread


private class EchoWebSocketListener : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
        println("WS: Connection opened")
        webSocket.send("Hello, it's SSaurel !")
        webSocket.send("What's up ?")
        webSocket.send(ByteString.decodeHex("deadbeef"))
        webSocket.close(NORMAL_CLOSURE_STATUS, "Goodbye !")
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
        println("WS Client: Error : " + t.message)
        t.printStackTrace()
    }

    companion object {
        private val NORMAL_CLOSURE_STATUS = 1000
    }
}

fun client() {
    Thread.sleep(1000)
    println("Creating client & connecting")
    val request = Request.Builder().url("ws://localhost:8080").build()
    val listener = EchoWebSocketListener()
    val client = OkHttpClient()
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
    server.handler = WebSocketHandler.Simple(WebsocketServerPojo::class.java)
    server.start()
    println("Server is running, press Enter to kill it")
    thread {
        try {
            client()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
    thread {
        try {
            Thread.sleep(2000)
            client()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
    server.join()
    System.`in`.read()
}
