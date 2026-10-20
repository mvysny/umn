package com.gitlab.mvysny.umn.proxy.adminclient

import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.proxy.AdminServer
import com.gitlab.mvysny.umn.proxy.MVStoreGlobalLogProvider
import com.gitlab.mvysny.umn.proxy.UMNProxyServer
import com.gitlab.mvysny.umn.proxy.server
import com.google.gson.JsonParseException
import io.javalin.Javalin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.expect
import java.nio.file.Files

class AdminClientTest {
    private lateinit var client: AdminClient
    @BeforeEach fun createClient() { client = createAdminClient("http://localhost:10145/admin/") }

    @Nested inner class WithoutRunningServer {
        @Test fun `user retrieval fails`() {
            assertFailsWith<ConnectException> {
                client.getUsers()
            }
        }
    }

    @Nested inner class WithInvalidServer {
        private lateinit var javalin: Javalin
        @BeforeEach fun startJavalin() {
            javalin = Javalin.create { config ->
                config.startup.showJavalinBanner = false
                config.routes.get("admin/users") { ctx -> ctx.result("foo") }
                config.routes.get("admin/stats/logsizes") { _ -> throw RuntimeException("dummy fail") }
                config.routes.exception(Exception::class.java) { e, ctx -> ctx.status(500).result(e.message!!) }
            }.start(10145)
        }
        @AfterEach fun stopJavalin() { javalin.stop() }

        @Test fun `json parsing fails with proper exception`() {
            assertFailsWith<JsonParseException> {
                client.getUsers()
            }
        }
        @Test fun `internal server error fails with proper exception`() {
            val ex = assertFailsWith<IOException> {
                client.getLogSizes()
            }
            assertContains(ex.message!!, "500: dummy fail")
        }
    }

    @Nested inner class WithRunningServer {
        private lateinit var adminServer: AdminServer
        @BeforeEach fun startServers() {
            val dbDir = Files.createTempDirectory("tmp").toFile().absoluteFile
            val lp = MVStoreGlobalLogProvider(dbDir)
            server = UMNProxyServer(InetSocketAddress.createUnresolved("127.0.0.1", 10144), null, lp)
            server.start()
            adminServer = AdminServer(InetSocketAddress.createUnresolved("127.0.0.1", 10145))
        }
        @AfterEach fun stopServers() {
            adminServer.closeQuietly()
            server.close()
        }

        @Test fun `404 not found`() {
            client = createAdminClient("http://localhost:10145/admin2/")
            assertFailsWith<FileNotFoundException> {
                client.getUsers()
            }
        }
        @Nested inner class SimpleUserRetrieval {
            @Test fun `by default there are no users`() {
                // by default there are no users
                expect(listOf()) { client.getUsers() }
            }
            @Test fun `sample users`() {
                AdminServer.mvstoreGlobalLog.createOrUpdateUser("duke leto atreides", "foo")
                AdminServer.mvstoreGlobalLog.createOrUpdateUser("baron vladimir harkonnen", "ihateatreides")
                expect(setOf("duke leto atreides", "baron vladimir harkonnen")) { client.getUsers().toSet() }
            }
        }
        @Nested inner class AddingUsers {
            @Test fun `adding user`() {
                client.createOrUpdateUser("adam", "example")
                expect(listOf("adam")) { client.getUsers() }
            }
            @Test fun `adding user with no password fails`() {
                val ex = assertFailsWith<IOException> {
                    client.createOrUpdateUser("adam", "")
                }
                assertContains(ex.message!!, "400: ")
                assertContains(ex.message!!, "password")
            }
        }
        @Nested inner class DeletingUsers {
            @Test fun `deleting user`() {
                client.createOrUpdateUser("adam", "example")
                expect(listOf("adam")) { client.getUsers() }
                client.deleteUser("adam")
                expect(listOf()) { client.getUsers() }
            }
        }

        @Test fun `log sizes`() {
            client.createOrUpdateUser("adam", "example")
            AdminServer.mvstoreGlobalLog.getLogMap("adam").put(0, "foo".toByteArray())
            expect(mapOf("adam" to 1L)) { client.getLogSizes() }
        }
    }
}
