package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.core.closeQuietly
import org.slf4j.LoggerFactory
import java.io.Closeable

/**
 * The UMN Proxy Server: the sync websocket on [Config.port], wss on [Config.secure] if set, and the REST admin on
 * [Config.adminPort], all over one global log in [Standalone.db]. Embeds in a test or an app:
 * ```kotlin
 * Main(Config(port = 0, adminPort = 0, proxy = Proxy(Standalone("/tmp/umn-db")))).use { server ->
 *     server.start()
 *     // sync through ws://127.0.0.1:${server.port}, administer through http://127.0.0.1:${server.adminPort}/admin
 * }
 * ```
 * One lifecycle, new → started → closed: [start] once, [close] to stop everything; another run takes another
 * instance. Thread-safe. [main] is the Docker image's and the distribution's entry point.
 */
public class Main(public val cfg: Config) : Closeable {
    private enum class State { NEW, STARTED, CLOSED }

    private var state = State.NEW
    private var globalLogClientProvider: GlobalLogClientProvider? = null
    private var proxyServer: UMNProxyServer? = null
    private var adminServer: AdminServer? = null

    /**
     * Opens the global log and starts listening; returns once every port is bound.
     * @throws IllegalStateException if already started, or closed.
     */
    @Synchronized
    public fun start() {
        check(state == State.NEW) { "start() runs once, on a new Main; this one is $state" }
        // before anything opens: a start() that throws halfway leaves a Main that only close() accepts
        state = State.STARTED
        val provider = cfg.proxy.createLogClientProvider()
        globalLogClientProvider = provider
        log.info("Using log provider: $provider")
        val proxy = UMNProxyServer(cfg.socketAddress, cfg.secure, provider)
        proxyServer = proxy  // before start(): a port already taken leaves Jetty half-started, for close() to stop
        proxy.start()
        adminServer = AdminServer(cfg.adminSocketAddress, (provider as MVStoreGlobalLogProvider).mvstorelog)
    }

    /**
     * The port plain ws is bound to: [Config.port], or the one the OS picked for `port = 0`.
     * @throws IllegalStateException unless started and not closed.
     */
    @get:Synchronized
    public val port: Int get() = running(proxyServer).port

    /**
     * The port the REST admin is bound to: [Config.adminPort], or the one the OS picked for `adminPort = 0`.
     * @throws IllegalStateException unless started and not closed.
     */
    @get:Synchronized
    public val adminPort: Int get() = running(adminServer).port

    private fun <T : Any> running(server: T?): T {
        check(state == State.STARTED && server != null) { "the server isn't running; it is $state" }
        return server
    }

    /**
     * Stops the servers and closes the global log. Does nothing on a closed or unstarted Main; after a [start] that
     * threw halfway, releases what did start.
     */
    @Synchronized
    override fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        adminServer?.closeQuietly()
        proxyServer?.closeQuietly()
        globalLogClientProvider?.closeQuietly()
    }

    override fun toString(): String = "Main(cfg=$cfg, state=$state)"

    public companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(Main::class.java)

        /**
         * Runs the UMN Proxy Server from `config.yaml` until the JVM is killed; see [Config.load].
         */
        @JvmStatic
        public fun main(args: Array<String>) {
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
                append("- Sync Server is running on ws://${cfg.host}:${main.port}\n")
                append("- Admin is running on http://${cfg.adminHost}:${main.adminPort}/admin\n")
                val secure = cfg.secure
                if (secure != null) {
                    append("- Secure Sync Server is running on wss://${secure.host}:${secure.port}\n")
                    if (secure.keystoreReloadSeconds > 0) {
                        append("  its keystore is checked for changes every ${secure.keystoreReloadSeconds}s\n")
                    } else {
                        append("  its keystore was read once; restart to load a new one\n")
                    }
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
