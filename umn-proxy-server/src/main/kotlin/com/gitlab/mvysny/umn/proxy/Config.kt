package com.gitlab.mvysny.umn.proxy

import org.eclipse.jetty.server.*
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress

data class Config(var port: Int = 8080,
                  var host: String = "0.0.0.0",
                  var adminPort: Int = 8081,
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

data class Secure(var port: Int = 8443,
                  var host: String = "0.0.0.0",
                  var pkcs12: String? = null) {

    fun registerJettyConnector(server: Server) {
        val p12 = requireNotNull(pkcs12) { "The pkcs12 key is missing!" }
        server.addConnector(createJettyConnector(File(p12), server))
    }

    private fun createJettyConnector(pkcs12: File, server: Server): ServerConnector {
        require(pkcs12.exists()) { "$pkcs12 does not exist" }
        require(pkcs12.isFile) { "$pkcs12 is not a file" }
        require(pkcs12.canRead()) { "$pkcs12 is not readable" }

        // taken from https://danielflower.github.io/2017/04/08/Lets-Encrypt-Certs-with-embedded-Jetty.html
        val config = HttpConfiguration()
        config.addCustomizer(SecureRequestCustomizer())
        config.addCustomizer(ForwardedRequestCustomizer())

        // Create the HTTPS end point
        val sslContextFactory = SslContextFactory()
        sslContextFactory.keyStoreType = "PKCS12"
        sslContextFactory.keyStorePath = pkcs12.absolutePath
        sslContextFactory.setKeyStorePassword("")
        sslContextFactory.setKeyManagerPassword("")
        val httpsConnector = ServerConnector(server, sslContextFactory, HttpConnectionFactory(config))
        httpsConnector.port = port
        httpsConnector.host = host
        server.addConnector(httpsConnector)
        return httpsConnector
    }
}
