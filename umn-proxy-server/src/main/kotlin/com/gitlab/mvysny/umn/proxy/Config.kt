package com.gitlab.mvysny.umn.proxy

import org.eclipse.jetty.server.*
import org.eclipse.jetty.util.ssl.KeyStoreScanner
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress

/**
 * The server's `config.yaml`.
 *
 * @property port plain ws; 0 binds a free one, which [Main.port] reads back.
 * @property adminPort the REST admin; 0 binds a free one, which [Main.adminPort] reads back.
 * @property proxy must have [Proxy.standalone] set: [Proxy]'s default has none.
 * @property host where plain ws listens. Localhost by default: plain ws carries the passwords unencrypted, so only
 *   local clients, or a local wss terminator such as Caddy, should reach it.
 * @property secure also serves wss, beside plain ws.
 */
public data class Config(public var port: Int = 10144,
                         public var host: String = "127.0.0.1",
                         public var adminPort: Int = 10145,
                         public var adminHost: String = "127.0.0.1",
                         public var proxy: Proxy = Proxy(),
                         public var secure: Secure? = null) {
    internal val socketAddress: InetSocketAddress get() = InetSocketAddress.createUnresolved(host, port)
    internal val adminSocketAddress: InetSocketAddress get() = InetSocketAddress.createUnresolved(adminHost, adminPort)

    internal companion object {
        /**
         * Loads the configuration file from the following locations:
         *
         * * `./config.yaml` (the current working directory)
         * * `/etc/umnproxyserver/config.yaml`
         *
         * If no configuration file is found, a default config is loaded.
         */
        fun load(): Config {
            val files = listOf(File("config.yaml"), File("/etc/umnproxyserver/config.yaml"))
            val file = files.firstOrNull { it.exists() } ?: return newEmpty()
            return file.inputStream().use { loadFrom(it) }
        }
        fun loadFrom(i: InputStream): Config = Yaml().loadAs(i, Config::class.java) ?: Config()
        fun newEmpty(): Config = Config().apply { proxy.standalone = Standalone() }
    }
}

/**
 * The `proxy:` stanza: where the global log lives.
 * @property standalone the only storage there is; [Main.start] fails while it's null.
 */
public data class Proxy(public var standalone: Standalone? = null) {
    internal fun createLogClientProvider(): GlobalLogClientProvider = when {
        standalone != null -> standalone!!.createLogClientProvider()
        else -> throw IllegalStateException("No log client provider has been defined, I don't know where to proxy the log commands!")
    }
}

/**
 * The `standalone:` stanza: the global log in an MVStore file of this JVM's own.
 * @property db the directory holding the file, `umn_proxy_log`; created if missing, but not its
 *   parent; relative to the working directory.
 */
public data class Standalone(public var db: String = "db") {
    internal fun createLogClientProvider(): GlobalLogClientProvider = MVStoreGlobalLogProvider(File(db).absoluteFile)
}

/**
 * The `secure:` stanza: serves wss on [host]:[port] with the certificate in the passwordless PKCS12 keystore [pkcs12].
 * @property keystoreReloadSeconds 0 reads [pkcs12] once at startup; N checks it every N seconds and serves a changed
 * one to new connections, dropping none. A symlink retargeted at another file counts as a change; a host-side move
 * behind a Docker bind mount of the single file goes unnoticed.
 */
public data class Secure(public var port: Int = 10146,
                         public var host: String = "0.0.0.0",
                         public var pkcs12: String? = null,
                         public var keystoreReloadSeconds: Int = 0) {

    internal fun createJettyConnector(server: Server): ServerConnector {
        val pkcs12 = File(requireNotNull(pkcs12) { "The pkcs12 key is missing!" })
        require(pkcs12.exists()) { "$pkcs12 does not exist" }
        require(pkcs12.isFile) { "$pkcs12 is not a file" }
        require(pkcs12.canRead()) { "$pkcs12 is not readable" }
        require(keystoreReloadSeconds >= 0) { "keystoreReloadSeconds must be 0 or greater, was $keystoreReloadSeconds" }

        // taken from https://danielflower.github.io/2017/04/08/Lets-Encrypt-Certs-with-embedded-Jetty.html
        val config = HttpConfiguration()
        config.addCustomizer(SecureRequestCustomizer())
        config.addCustomizer(ForwardedRequestCustomizer())

        // Create the HTTPS end point
        val sslContextFactory = SslContextFactory.Server()
        sslContextFactory.keyStoreType = "PKCS12"
        sslContextFactory.keyStorePath = pkcs12.absolutePath
        sslContextFactory.setKeyStorePassword("")
        sslContextFactory.setKeyManagerPassword("")
        if (keystoreReloadSeconds > 0) {
            server.addBean(KeyStoreScanner(sslContextFactory).apply { scanInterval = keystoreReloadSeconds })
        }
        val httpsConnector = ServerConnector(server, sslContextFactory, HttpConnectionFactory(config))
        httpsConnector.port = port
        httpsConnector.host = host
        return httpsConnector
    }
}
