package com.gitlab.mvysny.umn.proxy

import org.eclipse.jetty.server.Server
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.assertFailsWith
import kotlin.test.expect

class ConfigTest {
    @Test fun `load from empty config should fetch defaults`() {
        val cfg = Config.loadFrom(ByteArrayInputStream(byteArrayOf()))
        expect(InetSocketAddress.createUnresolved("0.0.0.0", 10144)) { cfg.socketAddress }
        expect(InetSocketAddress.createUnresolved("127.0.0.1", 10145)) { cfg.adminSocketAddress }
    }
    @Test fun `load from test config`() {
        val cfg = ConfigTest::class.java.getResourceAsStream("/testconfig.yaml").use { Config.loadFrom(it) }
        expect(InetSocketAddress.createUnresolved("127.0.0.1", 25)) { cfg.socketAddress }
        expect(InetSocketAddress.createUnresolved("0.0.0.0", 45)) { cfg.adminSocketAddress }
        expect("/some/weird/place") { cfg.proxy.standalone!!.db }
        expect(Secure(666, "127.0.0.1", "/opt/somewhere/keystore.p12")) { cfg.secure }
    }
    @Test fun `load without a config file falls back to the standalone defaults`() {
        // tests run in the module directory, which has no config.yaml
        expect(Config.newEmpty()) { Config.load() }
        expect("db") { Config.newEmpty().proxy.standalone!!.db }
    }
    @Test fun `proxy without a log provider fails`() {
        assertFailsWith<IllegalStateException> { Proxy().createLogClientProvider() }
    }

    @Nested inner class SecureConnector {
        @Test fun `missing pkcs12 fails`() {
            assertFailsWith<IllegalArgumentException> { Secure().createJettyConnector(Server()) }
        }
        @Test fun `nonexisting pkcs12 file fails`() {
            assertFailsWith<IllegalArgumentException> { Secure(pkcs12 = "/nonexisting/keystore.p12").createJettyConnector(Server()) }
        }
        @Test fun `pkcs12 directory fails`() {
            val dir = Files.createTempDirectory("tmp").toString()
            assertFailsWith<IllegalArgumentException> { Secure(pkcs12 = dir).createJettyConnector(Server()) }
        }
        @Test fun `creates the https connector`() {
            // the keystore is only read once the server starts
            val p12 = File.createTempFile("keystore", ".p12")
            val connector = Secure(port = 666, host = "127.0.0.1", pkcs12 = p12.absolutePath).createJettyConnector(Server())
            expect(666) { connector.port }
            expect("127.0.0.1") { connector.host }
        }
    }
}
