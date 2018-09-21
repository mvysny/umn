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
        server = UMNProxyServer(cfg.port, globalLogClientProvider)
        server.start()
        try {
            val restServer = AdminServer(cfg.adminPort)
            try {
                println("""
************************************************************************************************************************
Server is running on port ${cfg.port}, admin is running on port ${cfg.adminPort}. Press Enter to kill the server
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
