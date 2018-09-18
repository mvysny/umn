package com.gitlab.mvysny.umn.proxy

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.WebSocketListener
import org.eclipse.jetty.websocket.server.WebSocketHandler
import kotlin.concurrent.thread


class WebsocketServerPojo : WebSocketListener {
    private lateinit var session: Session
    override fun onWebSocketClose(statusCode: Int, reason: String) {
        log("Close: statusCode=$statusCode, reason=$reason")
    }

    override fun onWebSocketError(t: Throwable) {
        log("Error: " + t.message)
        t.printStackTrace()
    }

    override fun onWebSocketConnect(session: Session) {
        this.session = session
        log("Connect: " + session.remoteAddress.address)
        session.remote.sendString("Hello WebBrowser")
    }

    override fun onWebSocketText(message: String) {
        log("Message: $message")
        session.remote.sendString("Response from server: $message")
    }

    override fun onWebSocketBinary(payload: ByteArray, offset: Int, len: Int) {
        log("Binary: ${payload.copyOfRange(offset, len).joinToString(separator = ":") { (it.toInt() and 0xFF).toString(16) }}")
    }

    private fun log(msg: String) {
        println("${System.identityHashCode(session).toString(16)} WS Server: $msg")
    }
}

fun main(args: Array<String>) {
    val port = 8080
    val server = Server(port)
    server.handler = WebSocketHandler.Simple(WebsocketServerPojo::class.java)
    server.start()
    println("Server is running on port $port, press Enter to kill it")
    System.`in`.read()
    server.stop()
}
