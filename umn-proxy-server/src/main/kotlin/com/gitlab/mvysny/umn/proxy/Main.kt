package com.gitlab.mvysny.umn.proxy

import java.io.File

fun main(args: Array<String>) {
    val port = 8080
    val dbDir = File("db").absoluteFile
    MVStoreGlobalLogProvider(dbDir).use { globalLogClientProvider ->
        server = UMNProxyServer(port, globalLogClientProvider)
        server.start()
        try {
            val restServer = RestServer(port + 1)
            try {
                println("Server is running on port $port, press Enter to kill it")
                System.`in`.read()
            } finally {
                restServer.close()
            }
        } finally {
            server.close()
        }
    }
}
