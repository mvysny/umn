package com.gitlab.mvysny.umn.proxy.adminclient

import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectList
import com.github.mvysny.dynatest.expectThrows
import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.proxy.AdminServer
import com.gitlab.mvysny.umn.proxy.MVStoreGlobalLogProvider
import com.gitlab.mvysny.umn.proxy.UMNProxyServer
import com.gitlab.mvysny.umn.proxy.server
import com.google.gson.JsonParseException
import io.javalin.Javalin
import io.javalin.http.HttpResponseExceptionMapper
import io.javalin.http.InternalServerErrorResponse
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import kotlin.test.expect
import java.nio.file.Files

class AdminClientTest : DynaTest({
    lateinit var client: AdminClient
    beforeEach { client = createAdminClient("http://localhost:10145/admin/") }

    group("without running server") {
        test("user retrieval fails") {
            expectThrows(ConnectException::class) {
                client.getUsers()
            }
        }
    }

    group("with invalid server") {
        lateinit var javalin: Javalin
        beforeEach {
            javalin = Javalin.create { it.showJavalinBanner = false }
                    .get("admin/users") { ctx -> ctx.result("foo") }
                    .get("admin/stats/logsizes") { _ -> throw RuntimeException("dummy fail") }
                    .exception(Exception::class.java) { e, ctx -> HttpResponseExceptionMapper.handle(InternalServerErrorResponse(e.message!!), ctx) }
                    .start(10145)
        }
        afterEach { javalin.stop() }

        test("json parsing fails with proper exception") {
            expectThrows(JsonParseException::class) {
                client.getUsers()
            }
        }
        test("internal server error fails with proper exception") {
            expectThrows(IOException::class, "500: dummy fail") {
                client.getLogSizes()
            }
        }
    }

    group("with running server") {
        lateinit var adminServer: AdminServer
        beforeEach {
            val dbDir = Files.createTempDirectory("tmp").toFile().absoluteFile
            val lp = MVStoreGlobalLogProvider(dbDir)
            server = UMNProxyServer(InetSocketAddress.createUnresolved("127.0.0.1", 10144), null, lp)
            server.start()
            adminServer = AdminServer(InetSocketAddress.createUnresolved("127.0.0.1", 10145))
        }
        afterEach {
            adminServer.closeQuietly()
            server.close()
        }

        test("404 not found") {
            client = createAdminClient("http://localhost:10145/admin2/")
            expectThrows(FileNotFoundException::class) {
                client.getUsers()
            }
        }
        group("simple user retrieval") {
            test("by default there are no users") {
                // by default there are no users
                expectList() { client.getUsers() }
            }
            test("sample users") {
                AdminServer.mvstoreGlobalLog.createOrUpdateUser("duke leto atreides", "foo")
                AdminServer.mvstoreGlobalLog.createOrUpdateUser("baron vladimir harkonnen", "ihateatreides")
                expect(setOf("duke leto atreides", "baron vladimir harkonnen")) { client.getUsers().toSet() }
            }
        }
        group("adding users") {
            test("adding user") {
                client.createOrUpdateUser("adam", "example")
                expectList("adam") { client.getUsers() }
            }
            test("adding user with no password fails") {
                expectThrows(IOException::class, "400: Query parameter 'password' with value '' cannot be null or empty") {
                    client.createOrUpdateUser("adam", "")
                }
            }
        }
        group("deleting users") {
            test("deleting user") {
                client.createOrUpdateUser("adam", "example")
                expectList("adam") { client.getUsers() }
                client.deleteUser("adam")
                expectList() { client.getUsers() }
            }
        }

        test("log sizes") {
            client.createOrUpdateUser("adam", "example")
            AdminServer.mvstoreGlobalLog.getLogMap("adam").put(0, "foo".toByteArray())
            expect(mapOf("adam" to 1L)) { client.getLogSizes() }
        }
    }
})
