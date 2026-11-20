package compat

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.expect

@Testcontainers(disabledWithoutDocker = true)
class OnProdServerTest {
    @Nested inner class Wire : WireTestBattery(ProdServer.shared)
    @Nested inner class PhoneUpgrade : PhoneUpgradeTestBattery(ProdServer.shared)
    @Nested inner class MixedFleet : MixedFleetTestBattery(ProdServer.shared)
    @Nested inner class Admin : AdminTestBattery(ProdServer.shared)

    @Test fun `a JSON-body PUT fails loudly and creates nobody, as createOrUpdateUser2 warns`() {
        val user = UUID.randomUUID().toString()
        val admin = ProdServer.shared.adminUrl + "/admin/users"
        val http = HttpClient.newHttpClient()
        val put = HttpRequest.newBuilder(URI.create(admin))
            .PUT(HttpRequest.BodyPublishers.ofString("""{"username":"$user","password":"secret"}"""))
            .header("Content-Type", "application/json; charset=utf-8").build()
        expect(500) { http.send(put, HttpResponse.BodyHandlers.discarding()).statusCode() }
        val users = http.send(HttpRequest.newBuilder(URI.create(admin)).build(), HttpResponse.BodyHandlers.ofString()).body()
        assertFalse(user in users, users)
    }
}

/**
 * Holds HEAD's proxy server to [ProdServer]'s wire and admin API, so the server can change underneath 0.15 nodes.
 */
class OnHeadServerTest {
    @Nested inner class Wire : WireTestBattery(HeadServer.shared)
    @Nested inner class PhoneUpgrade : PhoneUpgradeTestBattery(HeadServer.shared)
    @Nested inner class MixedFleet : MixedFleetTestBattery(HeadServer.shared)
    @Nested inner class Admin : AdminTestBattery(HeadServer.shared)
}
