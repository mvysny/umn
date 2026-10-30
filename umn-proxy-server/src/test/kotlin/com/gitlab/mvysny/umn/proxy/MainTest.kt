package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.proxyclient.ProxyClient
import com.gitlab.mvysny.umn.proxyclient.destroy
import com.gitlab.mvysny.umn.sync.tester.addLogCreated
import com.gitlab.mvysny.umn.sync.tester.expectAsync
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.cert.Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
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
                createUser(httpClient)
                val users = httpClient.newCall(Request.Builder().url("http://127.0.0.1:51345/admin/users").build()).execute().use { it.body!!.string() }
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
                createUser(httpClient)
                val client = ProxyClient(httpClient, Request.Builder().url("wss://localhost:51346"), "user", "example")
                client.start()
                client.close()
            }
        } finally {
            httpClient.destroy()
        }
    }

    @Test fun `serves plain ws beside wss`() {
        val p12 = resource("localhost.p12")
        val cfg = Config(port = 51344, host = "127.0.0.1", adminPort = 51345, proxy = Proxy(Standalone(File(Files.createTempDirectory("tmp").toFile(), "db").absolutePath)),
            secure = Secure(port = 51346, host = "127.0.0.1", pkcs12 = p12.absolutePath))
        val httpClient = OkHttpClient()
        try {
            Main(cfg).use { main ->
                main.start()
                createUser(httpClient)
                val client = ProxyClient(httpClient, Request.Builder().url("ws://127.0.0.1:51344"), "user", "example")
                client.start()
                client.close()
            }
        } finally {
            httpClient.destroy()
        }
    }

    /**
     * `localhost-renewed.p12` stands in for a renewed certificate: another self-signed `localhost` one, made the same
     * way as `localhost.p12`.
     */
    @Test fun `serves a keystore moved over the old one`() {
        val dir = Files.createTempDirectory("tmp")
        Files.copy(resource("localhost.p12").toPath(), dir.resolve("keystore.p12"))
        servesRenewedKeystore(dir.resolve("keystore.p12")) {
            Files.copy(resource("localhost-renewed.p12").toPath(), dir.resolve("keystore.p12.new"))
            Files.move(dir.resolve("keystore.p12.new"), dir.resolve("keystore.p12"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /**
     * Certbot's layout: a symlinked directory, and a symlink retargeted at each renewal's new file.
     */
    @Test fun `serves a keystore symlink retargeted at a new file`() {
        val tmp = Files.createTempDirectory("tmp")
        val real = Files.createDirectory(tmp.resolve("real"))
        val link = Files.createSymbolicLink(tmp.resolve("link"), real)
        Files.copy(resource("localhost.p12").toPath(), real.resolve("keystore1.p12"))
        Files.createSymbolicLink(real.resolve("keystore.p12"), real.resolve("keystore1.p12"))
        servesRenewedKeystore(link.resolve("keystore.p12")) {
            Files.copy(resource("localhost-renewed.p12").toPath(), real.resolve("keystore2.p12"))
            Files.createSymbolicLink(real.resolve("keystore.p12.new"), real.resolve("keystore2.p12"))
            Files.move(real.resolve("keystore.p12.new"), real.resolve("keystore.p12"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /**
     * Serves [keystore] (`localhost.p12`) with `keystoreReloadSeconds: 1`, then checks that after [renew] puts
     * `localhost-renewed.p12` in its place, new connections get the renewed certificate and an open one keeps syncing.
     */
    private fun servesRenewedKeystore(keystore: Path, renew: () -> Unit) {
        val cfg = Config(port = 51344, host = "127.0.0.1", adminPort = 51345, proxy = Proxy(Standalone(File(Files.createTempDirectory("tmp").toFile(), "db").absolutePath)),
            secure = Secure(port = 51346, host = "127.0.0.1", pkcs12 = keystore.toString(), keystoreReloadSeconds = 1))
        val trust = trustManager("localhost.p12", "localhost-renewed.p12")
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val httpClient = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust).build()
        try {
            Main(cfg).use { main ->
                main.start()
                createUser(httpClient)
                val client = ProxyClient(httpClient, Request.Builder().url("wss://localhost:51346"), "user", "example")
                val received = AtomicInteger()
                client.setReceiverCallback(0L) { received.incrementAndGet() }
                client.start()
                expect(certificate("localhost.p12")) { servedCertificate(trust) }

                renew()
                expectAsync(certificate("localhost-renewed.p12"), 10_000L) { servedCertificate(trust) }

                client.addLogCreated("foo")
                expectAsync(1) { received.get() }
                client.close()
            }
        } finally {
            httpClient.destroy()
        }
    }

    private fun resource(name: String): File = File(MainTest::class.java.getResource("/$name")!!.toURI())

    private fun certificate(p12: String): Certificate {
        val keystore = KeyStore.getInstance("PKCS12").apply { resource(p12).inputStream().use { load(it, CharArray(0)) } }
        return keystore.getCertificate(keystore.aliases().toList().single())
    }

    private fun trustManager(vararg p12s: String): X509TrustManager {
        val trusted = KeyStore.getInstance("PKCS12").apply {
            load(null)
            p12s.forEach { setCertificateEntry(it, certificate(it)) }
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }
            .trustManagers.single() as X509TrustManager
    }

    /**
     * The certificate the secure port serves a new connection. A fresh [SSLContext] each call, so no TLS session is
     * resumed from before a reload.
     */
    private fun servedCertificate(trust: X509TrustManager): Certificate {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return (ssl.socketFactory.createSocket("localhost", 51346) as SSLSocket).use {
            it.startHandshake()
            it.session.peerCertificates[0]
        }
    }

    private fun createUser(httpClient: OkHttpClient) {
        val put = Request.Builder().url("http://127.0.0.1:51345/admin/users?username=user&password=example").put(ByteArray(0).toRequestBody()).build()
        httpClient.newCall(put).execute().use { expect(204) { it.code } }
    }
}
