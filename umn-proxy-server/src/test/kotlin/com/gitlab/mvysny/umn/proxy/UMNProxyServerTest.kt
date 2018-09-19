package com.gitlab.mvysny.umn.proxy

import com.github.mvysny.dynatest.DynaTest
import com.gitlab.mvysny.umn.proxyclient.ProxyClient
import com.gitlab.mvysny.umn.sync.tester.globalLogClientTestBattery
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class UMNProxyServerTest : DynaTest({
    beforeEach {
        val dbDir = createTempDir().absoluteFile
        val lp = MVStoreGlobalLogProvider(dbDir)
        server = UMNProxyServer(8080, lp)
        server.start()
    }
    afterEach { server.close() }

    globalLogClientTestBattery {
        val client = OkHttpClient()
        val request = Request.Builder().url("ws://localhost:8080")
        ProxyClient(client, request, "user", "example")
    }
})
