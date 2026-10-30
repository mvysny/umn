package com.gitlab.mvysny.umn.proxy.adminclient

import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.proxy.AdminServer
import com.gitlab.mvysny.umn.proxy.MVStoreGlobalLogProvider
import com.gitlab.mvysny.umn.proxy.UMNProxyServer
import com.gitlab.mvysny.umn.sync.InvalidUsernamePasswordException
import com.google.gson.JsonParseException
import io.javalin.Javalin
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import kotlin.test.assertFalse
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
        private lateinit var server: UMNProxyServer
        private lateinit var adminServer: AdminServer
        private lateinit var lp: MVStoreGlobalLogProvider
        @BeforeEach fun startServers() {
            val dbDir = Files.createTempDirectory("tmp").toFile().absoluteFile
            lp = MVStoreGlobalLogProvider(dbDir)
            server = UMNProxyServer(InetSocketAddress.createUnresolved("127.0.0.1", 10144), null, lp)
            server.start()
            adminServer = AdminServer(InetSocketAddress.createUnresolved("127.0.0.1", 10145), lp.mvstorelog)
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
                lp.mvstorelog.createOrUpdateUser("duke leto atreides", "foo")
                lp.mvstorelog.createOrUpdateUser("baron vladimir harkonnen", "ihateatreides")
                expect(setOf("duke leto atreides", "baron vladimir harkonnen")) { client.getUsers().toSet() }
            }
        }
        /**
         * Fails unless [username] logs in with [password].
         */
        private fun assertLogin(username: String, password: String) {
            lp.mvstorelog.newClient(username, password).use { it.start() }
        }

        @Suppress("DEPRECATION")
        @Nested inner class AddingUsersViaQuery {
            @Test fun `adding user`() {
                client.createOrUpdateUser("adam", "example")
                expect(listOf("adam")) { client.getUsers() }
                assertLogin("adam", "example")
            }
            @Test fun `adding user with no password fails`() {
                val ex = assertFailsWith<IOException> {
                    client.createOrUpdateUser("adam", "")
                }
                assertContains(ex.message!!, "400: ")
                assertContains(ex.message!!, "Query parameter 'password'")
                expect(listOf()) { client.getUsers() }
            }
        }
        @Nested inner class AddingUsersViaJson {
            @Test fun `adding user`() {
                client.createOrUpdateUser2("adam", "example")
                expect(listOf("adam")) { client.getUsers() }
                assertLogin("adam", "example")
            }
            @Test fun `updating password`() {
                client.createOrUpdateUser2("adam", "example")
                client.createOrUpdateUser2("adam", "new")
                assertLogin("adam", "new")
                assertFailsWith<InvalidUsernamePasswordException> { assertLogin("adam", "example") }
            }
            @Test fun `non-ASCII credentials`() {
                client.createOrUpdateUser2("ádám", "příliš žluťoučký 日本")
                expect(listOf("ádám")) { client.getUsers() }
                assertLogin("ádám", "příliš žluťoučký 日本")
            }
            @Test fun `adding user with no password fails`() {
                val ex = assertFailsWith<IOException> {
                    client.createOrUpdateUser2("adam", "")
                }
                assertContains(ex.message!!, "400: Expected a JSON body")
                expect(listOf()) { client.getUsers() }
            }
        }

        /**
         * Requests [AdminClient] never sends, the way `curl` or a hand-written client might.
         */
        @Nested inner class RawPut {
            private fun put(query: String, body: String, contentType: String? = null): Pair<Int, String> {
                val request = Request.Builder().url("http://localhost:10145/admin/users$query")
                    .put(body.toRequestBody(contentType?.toMediaType()))
                    .build()
                return client.client.newCall(request).execute().use { it.code to it.body!!.string() }
            }
            private fun assertRejected(query: String, body: String) {
                val (code, message) = put(query, body)
                expect(400, message) { code }
                assertContains(message, "Expected a JSON body")
                assertFalse(message.contains("secret"), message)
                expect(listOf()) { client.getUsers() }
            }

            @Test fun `curl -d sends a form Content-Type, which is ignored`() {
                expect(204) { put("", """{"username":"adam","password":"secret"}""", "application/x-www-form-urlencoded").first }
                assertLogin("adam", "secret")
            }
            @Test fun `both query parameters win over the body`() {
                expect(204) { put("?username=adam&password=query", """{"username":"eve","password":"secret"}""").first }
                expect(listOf("adam")) { client.getUsers() }
                assertLogin("adam", "query")
            }
            @Test fun `one query parameter alone means the body holds both`() {
                expect(204) { put("?username=adam", """{"username":"eve","password":"secret"}""").first }
                expect(listOf("eve")) { client.getUsers() }
            }
            @Test fun `one query parameter and no body`() { assertRejected("?username=adam", "") }
            @Test fun `no body`() { assertRejected("", "") }
            @Test fun `malformed JSON`() { assertRejected("", """{"username":"adam","password":"secret"""") }
            @Test fun `JSON array`() { assertRejected("", """["adam","secret"]""") }
            @Test fun `missing password`() { assertRejected("", """{"username":"adam"}""") }
            @Test fun `null password`() { assertRejected("", """{"username":"adam","password":null}""") }
            @Test fun `object as username`() { assertRejected("", """{"username":{"name":"adam"},"password":"secret"}""") }
            @Test fun `empty username`() { assertRejected("", """{"username":"","password":"secret"}""") }
        }
        @Nested inner class DeletingUsers {
            @Test fun `deleting user`() {
                client.createOrUpdateUser2("adam", "example")
                expect(listOf("adam")) { client.getUsers() }
                client.deleteUser("adam")
                expect(listOf()) { client.getUsers() }
            }
        }

        @Test fun `log sizes`() {
            client.createOrUpdateUser2("adam", "example")
            lp.mvstorelog.getLogMap("adam").put(0, "foo".toByteArray())
            expect(mapOf("adam" to 1L)) { client.getLogSizes() }
        }
    }
}
