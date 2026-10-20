package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.proxyclient.ProxyClient
import com.gitlab.mvysny.umn.proxyclient.destroy
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
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
}
