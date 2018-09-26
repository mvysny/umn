package com.gitlab.mvysny.umn.proxy

import com.github.mvysny.dynatest.DynaTest
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import kotlin.test.expect

class ConfigTest : DynaTest({
    test("load from empty config should fetch defaults") {
        val cfg = Config.loadFrom(ByteArrayInputStream(byteArrayOf()))
        expect(InetSocketAddress.createUnresolved("0.0.0.0", 8080)) { cfg.socketAddress }
        expect(InetSocketAddress.createUnresolved("127.0.0.1", 8081)) { cfg.adminSocketAddress }
    }
    test("load from test config") {
        val cfg = ConfigTest::class.java.getResourceAsStream("/testconfig.yaml").use { Config.loadFrom(it) }
        expect(InetSocketAddress.createUnresolved("127.0.0.1", 25)) { cfg.socketAddress }
        expect(InetSocketAddress.createUnresolved("0.0.0.0", 45)) { cfg.adminSocketAddress }
        expect("/some/weird/place") { cfg.proxy.standalone!!.db }
        expect(Secure(666, "127.0.0.1", "/opt/somewhere/keystore.p12")) { cfg.secure }
    }
})
