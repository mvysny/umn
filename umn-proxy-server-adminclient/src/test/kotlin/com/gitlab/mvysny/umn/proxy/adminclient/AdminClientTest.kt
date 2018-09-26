package com.gitlab.mvysny.umn.proxy.adminclient

import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectList
import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.proxy.AdminServer
import com.gitlab.mvysny.umn.proxy.MVStoreGlobalLogProvider
import com.gitlab.mvysny.umn.proxy.UMNProxyServer
import com.gitlab.mvysny.umn.proxy.server
import java.net.InetSocketAddress
import kotlin.test.expect

class AdminClientTest : DynaTest({
    lateinit var adminServer: AdminServer
    lateinit var client: AdminClient
    beforeEach {
        val dbDir = createTempDir().absoluteFile
        val lp = MVStoreGlobalLogProvider(dbDir)
        server = UMNProxyServer(InetSocketAddress.createUnresolved("127.0.0.1", 10144), null, lp)
        server.start()
        adminServer = AdminServer(InetSocketAddress.createUnresolved("127.0.0.1", 10145))
        client = createAdminClient("http://localhost:10145/admin/")
    }
    afterEach {
        adminServer.closeQuietly()
        server.close()
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
})
