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
 * @property host where plain ws listens. Localhost by default: plain ws carries the passwords unencrypted, so only a
 *   local wss terminator (Caddy, or [secure]) should reach it.
 */
data class Config(var port: Int = 10144,
                  var host: String = "127.0.0.1",
                  var adminPort: Int = 10145,
                  var adminHost: String = "127.0.0.1",
                  var proxy: Proxy = Proxy(),
                  var secure: Secure? = null) {
    val socketAddress: InetSocketAddress get() = InetSocketAddress.createUnresolved(host, port)
    val adminSocketAddress: InetSocketAddress get() = InetSocketAddress.createUnresolved(adminHost, adminPort)

    companion object {
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

data class Proxy(var standalone: Standalone? = null) {
    fun createLogClientProvider(): GlobalLogClientProvider = when {
        standalone != null -> standalone!!.createLogClientProvider()
        else -> throw IllegalStateException("No log client provider has been defined, I don't know where to proxy the log commands!")
    }
}

data class Standalone(var db: String = "db") {
    fun createLogClientProvider(): GlobalLogClientProvider = MVStoreGlobalLogProvider(File(db).absoluteFile)
}

/**
 * The `secure:` stanza: serves wss on [host]:[port] with the certificate in the passwordless PKCS12 keystore [pkcs12].
 * @property keystoreReloadSeconds 0 reads [pkcs12] once at startup; N checks it every N seconds and serves a changed
 * one to new connections, dropping none. A symlink retargeted at another file counts as a change; a host-side move
 * behind a Docker bind mount of the single file goes unnoticed.
 */
data class Secure(var port: Int = 10146,
                  var host: String = "0.0.0.0",
                  var pkcs12: String? = null,
                  var keystoreReloadSeconds: Int = 0) {

    fun createJettyConnector(server: Server): ServerConnector {
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
