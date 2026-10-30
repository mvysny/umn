package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.core.AutoTypeResolver
import com.gitlab.mvysny.umn.core.InMemoryUMN
import com.gitlab.mvysny.umn.core.JavaDocumentSerializer
import com.gitlab.mvysny.umn.proxyclient.ProxyClient
import com.gitlab.mvysny.umn.proxyclient.destroy
import com.gitlab.mvysny.umn.sync.SyncerStatus
import com.gitlab.mvysny.umn.sync.SyncingUMN
import com.gitlab.mvysny.umn.sync.syncing
import com.gitlab.mvysny.umn.sync.tester.expectAsync
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.Closeable
import java.io.File
import java.io.Serializable
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.expect
import kotlin.test.fail

/**
 * wss through Caddy, which terminates TLS with a certificate from its own local CA and forwards plain ws to the
 * server; see `CONTRIBUTING.md`. Skipped without `caddy` on the PATH.
 */
@EnabledIf("caddyInstalled")
class CaddyTest {
    private val server = Main(Config(port = freePort(), host = "127.0.0.1", adminPort = freePort(),
        proxy = Proxy(Standalone(File(Files.createTempDirectory("tmp").toFile(), "db").absolutePath)))).apply { start() }
    private val closeables = mutableListOf<Closeable>()

    @AfterEach fun close() {
        closeables.reversed().forEach { it.close() }
        server.close()
    }

    @Test fun `syncs both ways`() {
        val caddy = caddy()
        val (a, b) = pair(caddy)
        syncsBothWays(a, b)
    }

    /**
     * `caddy reload` force-closes every proxied websocket.
     */
    @Test fun `a reload drops the connection, and the syncer reconnects`() {
        val caddy = caddy()
        val (a, b) = pair(caddy)
        caddy.reload { it.replace("reverse_proxy", "header X-Reloaded 1\n\treverse_proxy") }
        expectAsync(true, 10_000) { a.statuses.any { it is SyncerStatus.Offline } }
        val fromA = a.write("written while offline")
        expectAsync(true, 15_000) { b.has(fromA) }
    }

    @Test fun `a renewal keeps the connection`() {
        val caddy = caddy(lifetime = "30s")
        val (a, b) = pair(caddy)
        val serial = caddy.servedSerial()
        expectAsync(true, 60_000) { caddy.servedSerial() != serial }
        syncsBothWays(a, b)
        expect(listOf(SyncerStatus.Stopped, SyncerStatus.Connecting, SyncerStatus.Connected)) { a.statuses.toList() }
        expect(listOf(SyncerStatus.Stopped, SyncerStatus.Connecting, SyncerStatus.Connected)) { b.statuses.toList() }
    }

    /**
     * Caddy has no timeout on a proxied websocket. Takes `CADDY_IDLE_MINUTES`, so it runs only when asked to.
     */
    @Test @EnabledIfEnvironmentVariable(named = "CADDY_IDLE_MINUTES", matches = "\\d+")
    fun `an idle connection stays open`() {
        val caddy = caddy()
        val (a, b) = pair(caddy)
        Thread.sleep(Duration.ofMinutes(System.getenv("CADDY_IDLE_MINUTES").toLong()).toMillis())
        syncsBothWays(a, b)
        expect(listOf(SyncerStatus.Stopped, SyncerStatus.Connecting, SyncerStatus.Connected)) { a.statuses.toList() }
    }

    private fun caddy(lifetime: String? = null): Caddy = Caddy(server.cfg.port, lifetime).also { closeables.add(it) }

    private fun pair(caddy: Caddy): Pair<Node, Node> {
        val user = "user"
        val put = Request.Builder().url("http://127.0.0.1:${server.cfg.adminPort}/admin/users?username=$user&password=example").put(ByteArray(0).toRequestBody()).build()
        OkHttpClient().also { http -> http.newCall(put).execute().use { expect(204) { it.code } } }.destroy()
        val nodes = Node(caddy, user) to Node(caddy, user)
        closeables.addAll(nodes.toList())
        nodes.toList().forEach { node -> expectAsync(SyncerStatus.Connected, 10_000) { node.umn.syncer.status } }
        return nodes
    }

    private fun syncsBothWays(a: Node, b: Node) {
        val fromA = a.write("from A")
        val fromB = b.write("from B")
        expectAsync(true, 10_000) { b.has(fromA) }
        expectAsync(true, 10_000) { a.has(fromB) }
    }

    data class Note(val text: String) : Serializable

    /**
     * A node syncing through [caddy], pinging every minute as [ProxyClient] recommends.
     */
    class Node(caddy: Caddy, user: String) : Closeable {
        private val http = caddy.httpClient().newBuilder().pingInterval(1, TimeUnit.MINUTES).build()
        val statuses = CopyOnWriteArrayList<SyncerStatus>()
        val umn: SyncingUMN = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer)
            .syncing({ ProxyClient(http, Request.Builder().url("wss://localhost:${caddy.port}"), user, "example") }, JavaDocumentSerializer)

        init {
            umn.syncer.setStatusListener { statuses.add(it) }
            umn.syncer.start()
        }

        fun write(text: String): UUID = umn.create(Note(text))
        fun has(id: UUID): Boolean = umn.exists(id)

        override fun close() {
            umn.close()
            http.destroy()
        }
    }

    /**
     * `caddy run` as the user, with its data and config in a temp dir, so it shares nothing with a system Caddy.
     * @param lifetime the leaf certificate's lifetime; null keeps Caddy's default of 12 hours.
     */
    class Caddy(upstreamPort: Int, lifetime: String? = null) : Closeable {
        private val dir: File = Files.createTempDirectory("caddy").toFile()
        val port = freePort()
        private val adminPort = freePort()
        private val caddyfile = File(dir, "Caddyfile")
        private val log = File(dir, "caddy.log")

        init {
            // skip_install_trust: the tests trust the local CA's root themselves, the system trust store stays alone.
            // auto_https disable_redirects: the redirect server would listen on :80, which a user can't bind.
            // default_sni: the JDK sends no SNI for a dotless host such as localhost, and Caddy fails a handshake
            // without one.
            // renew_interval: checks every 10 minutes by default, too rarely for a short lifetime.
            caddyfile.writeText("""
                {
                	skip_install_trust
                	admin localhost:$adminPort
                	auto_https disable_redirects
                	default_sni localhost
                	renew_interval 5s
                }
                localhost:$port {
                	tls {
                		issuer internal {
                			${if (lifetime != null) "lifetime $lifetime" else ""}
                		}
                	}
                	reverse_proxy 127.0.0.1:$upstreamPort
                }
            """.trimIndent())
        }

        private val process: Process = caddy("run", "--config", caddyfile.absolutePath).start()

        /**
         * Trusts the root of Caddy's local CA, which `caddy run` creates on its first start.
         */
        private val trust: X509TrustManager by lazy {
            val root = File(dir, "data/caddy/pki/authorities/local/root.crt").inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            val trusted = KeyStore.getInstance("PKCS12").apply { load(null); setCertificateEntry("caddy", root) }
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }
                .trustManagers.single() as X509TrustManager
        }

        init {
            val deadline = System.currentTimeMillis() + 10_000
            while (runCatching { servedSerial() }.isFailure) {
                if (!process.isAlive || System.currentTimeMillis() > deadline) {
                    close()
                    fail("Caddy didn't start:\n${log.readText()}")
                }
                Thread.sleep(50)
            }
        }

        private fun caddy(vararg args: String): ProcessBuilder = ProcessBuilder("caddy", *args).directory(dir)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .apply {
                environment()["XDG_DATA_HOME"] = File(dir, "data").absolutePath
                environment()["XDG_CONFIG_HOME"] = File(dir, "config").absolutePath
            }

        fun httpClient(): OkHttpClient {
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust).build()
        }

        /**
         * Rewrites the Caddyfile with [edit] and `caddy reload`s it; an unchanged Caddyfile reloads nothing.
         */
        fun reload(edit: (String) -> String) {
            caddyfile.writeText(edit(caddyfile.readText()))
            val reload = caddy("reload", "--config", caddyfile.absolutePath, "--address", "localhost:$adminPort").start()
            if (reload.waitFor() != 0) fail("caddy reload failed:\n${log.readText()}")
        }

        /**
         * The serial of the leaf certificate a new connection gets. A fresh [SSLContext] each call, so no TLS session
         * is resumed.
         */
        fun servedSerial(): String {
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            return (ssl.socketFactory.createSocket("localhost", port) as SSLSocket).use {
                it.startHandshake()
                (it.session.peerCertificates[0] as X509Certificate).serialNumber.toString(16)
            }
        }

        override fun close() {
            process.destroy()
            process.waitFor()
            dir.deleteRecursively()
        }
    }

    companion object {
        @JvmStatic fun caddyInstalled(): Boolean =
            System.getenv("PATH").split(File.pathSeparator).any { File(it, "caddy").canExecute() }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
