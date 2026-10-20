package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.core.closeQuietly
import org.slf4j.LoggerFactory
import java.io.Closeable

/**
 * The UMN Proxy Server implementation, with given [cfg]. Call [main] to start.
 */
class Main(val cfg: Config) : Closeable {
    override fun close() {
        adminServer?.closeQuietly()
        proxyServer?.closeQuietly()
        globalLogClientProvider?.closeQuietly()
    }

    private var globalLogClientProvider: GlobalLogClientProvider? = null
    private var proxyServer: UMNProxyServer? = null
    private var adminServer: AdminServer? = null

    fun start() {
        globalLogClientProvider = cfg.proxy.createLogClientProvider()
        log.info("Using log provider: $globalLogClientProvider")
        proxyServer = UMNProxyServer(cfg.socketAddress, cfg.secure, globalLogClientProvider!!)
        proxyServer!!.start()
        server = proxyServer!!

        adminServer = AdminServer(cfg.adminSocketAddress)
    }

    companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(Main::class.java)

        /**
         * Starts the UMN Proxy Server.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            val cfg = Config.load()
            log.info("Using config: $cfg")
            val main = Main(cfg)
            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    log.info("Shutting down")
                    main.close()
                    log.info("Shut down cleanly")
                } catch (t: Throwable) {
                    log.error("Failed to shut down cleanly", t)
                }
            })
            main.start()
            println(buildString {
                append("\n")
                append("********************************************************************[ UMN Proxy Server ]***\n")
                append("- Sync Server is running on ws://${cfg.socketAddress}\n")
                append("- Admin is running on http://${cfg.adminSocketAddress}/admin\n")
                if (cfg.secure != null) {
                    append("- Secure Sync Server is running on wss://${cfg.secure!!.host}:${cfg.secure!!.port}\n")
                } else {
                    append("- Secure Sync Server access disabled\n")
                }
                append("******************************************************************************************\n")
            })
            loop()
        }

        private fun loop() {
            // endless loop, to continue running in docker-compose where there is no stdin
            System.out.println("Press CTRL+C to terminate")
            while(true) {
                Thread.sleep(Long.MAX_VALUE)
            }
        }
    }
}
