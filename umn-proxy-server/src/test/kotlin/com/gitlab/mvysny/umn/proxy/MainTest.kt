package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.proxyclient.ProxyClient
import com.gitlab.mvysny.umn.proxyclient.destroy
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.expect

class MainTest {
    @Test fun `starts the proxy and the admin server`() {
        val dbDir = File(Files.createTempDirectory("tmp").toFile(), "db")
        val cfg = Config(port = 51344, host = "127.0.0.1", adminPort = 51345, proxy = Proxy(Standalone(dbDir.absolutePath)))
        val httpClient = OkHttpClient()
        try {
            Main(cfg).use { main ->
                main.start()
                expect(true) { dbDir.isDirectory }
                AdminServer.mvstoreGlobalLog.createOrUpdateUser("user", "example")
                val users = httpClient.newCall(Request.Builder().url("http://127.0.0.1:51345/admin/users").build()).execute().use { it.body()!!.string() }
                expect("[\"user\"]") { users }

                val client = ProxyClient(httpClient, Request.Builder().url("ws://127.0.0.1:51344"), "user", "example")
                client.start()
                client.close()
            }
        } finally {
            httpClient.destroy()
        }
    }

    /**
     * `localhost.p12` is a self-signed `localhost` certificate, made as the README makes the production keystore:
     * `openssl pkcs12 -export … -passout "pass:"`.
     */
    @Test fun `serves wss on the secure port`() {
        val p12 = File(MainTest::class.java.getResource("/localhost.p12")!!.toURI())
        val cfg = Config(port = 51344, host = "127.0.0.1", adminPort = 51345, proxy = Proxy(Standalone(File(Files.createTempDirectory("tmp").toFile(), "db").absolutePath)),
            secure = Secure(port = 51346, host = "127.0.0.1", pkcs12 = p12.absolutePath))
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(KeyStore.getInstance("PKCS12").apply { p12.inputStream().use { load(it, CharArray(0)) } })
        }.trustManagers.single() as X509TrustManager
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val httpClient = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust).build()
        try {
            Main(cfg).use { main ->
                main.start()
                AdminServer.mvstoreGlobalLog.createOrUpdateUser("user", "example")
                val client = ProxyClient(httpClient, Request.Builder().url("wss://localhost:51346"), "user", "example")
                client.start()
                client.close()
            }
        } finally {
            httpClient.destroy()
        }
    }
}
