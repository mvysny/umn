package com.gitlab.mvysny.umn.proxy

import org.slf4j.LoggerFactory

class Main {
    companion object {
        @JvmStatic
        val log = LoggerFactory.getLogger(Main::class.java)
    }
}

fun main(args: Array<String>) {
    val cfg = Config.load()
    Main.log.info("Using config: $cfg")
    val globalLogProvider = cfg.proxy.createLogClientProvider()
    Main.log.info("Using log provider: $globalLogProvider")
    globalLogProvider.use { globalLogClientProvider ->
        server = UMNProxyServer(cfg.socketAddress, cfg.secure, globalLogClientProvider)
        server.start()
        try {
            val restServer = AdminServer(cfg.adminSocketAddress)
            try {
                println("""
************************************************************************************************************************
Server is running on ${cfg.socketAddress}, admin is running on ${cfg.adminSocketAddress}. Press Enter to kill the server
************************************************************************************************************************
""")
                System.`in`.read()
            } finally {
                restServer.close()
            }
        } finally {
            server.close()
        }
    }
}
