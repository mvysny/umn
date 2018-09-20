package com.gitlab.mvysny.umn.proxy

import com.github.mvysny.dynatest.DynaTest
import java.io.ByteArrayInputStream
import kotlin.test.expect

class ConfigTest : DynaTest({
    test("load from empty config should fetch defaults") {
        val cfg = Config.loadFrom(ByteArrayInputStream(byteArrayOf()))
        expect(8080) { cfg.port }
        expect(8081) { cfg.adminPort }
    }
    test("load from test config") {
        val cfg = ConfigTest::class.java.getResourceAsStream("/testconfig.yaml").use { Config.loadFrom(it) }
        expect(25) { cfg.port }
        expect(45) { cfg.adminPort }
        expect("/some/weird/place") { cfg.proxy.standalone!!.db }
    }
})
