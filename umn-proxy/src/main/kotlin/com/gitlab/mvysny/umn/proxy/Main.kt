package com.gitlab.mvysny.umn.proxy

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.WebSocketListener
import org.eclipse.jetty.websocket.api.annotations.*
import org.eclipse.jetty.websocket.server.WebSocketHandler
import kotlin.concurrent.thread


class WebsocketServerPojo : WebSocketListener {
    override fun onWebSocketClose(statusCode: Int, reason: String) {
        println("WS Server: Close: statusCode=$statusCode, reason=$reason")
    }

    override fun onWebSocketError(t: Throwable) {
        println("WS Server: Error: " + t.message)
        t.printStackTrace()
    }

    override fun onWebSocketConnect(session: Session) {
        System.out.println("WS Server: Connect: " + session.getRemoteAddress().getAddress())
        session.remote.sendString("Hello Webbrowser")
    }

    override fun onWebSocketText(message: String) {
        println("WS Server: Message: $message")
    }

    override fun onWebSocketBinary(payload: ByteArray, offset: Int, len: Int) {
        println("WS Server: Binary: $payload $offset $len")
    }
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
    server.join()
    System.`in`.read()
}
