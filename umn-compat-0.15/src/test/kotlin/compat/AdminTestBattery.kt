package compat

import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E: [server]'s admin REST API answers the 0.15 `AdminClient` as the 0.12 server does: the same paths, success
 * statuses and JSON. The `Content-Type` and the error statuses and bodies differ between servers; the client reads
 * neither, it only needs an error to be one.
 */
abstract class AdminTestBattery(private val server: ProxyServer) {
    private val http = HttpClient.newHttpClient()

    private fun call(method: String, path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create(server.adminUrl + path))
            .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())

    private fun users(): String = call("GET", "/admin/users").run {
        assertEquals(200, statusCode())
        body()
    }

    @Test
    fun `E1 a user is created, listed, updated and deleted`() {
        val account = Account(server)
        assertTrue(users().matches(Regex("""\[(".*",)*"${account.user}"(,".*")*]""")), users())

        assertEquals(204, call("PUT", "/admin/users?username=${account.user}&password=new").statusCode())
        assertEquals(listOf("started"), Head.run("wire-login", server.wsUrl, account.user, "new"))

        assertEquals(204, call("DELETE", "/admin/users/${account.user}").statusCode())
        assertFalse(account.user in users(), users())
        assertEquals(204, call("DELETE", "/admin/users/${account.user}").statusCode())
    }

    @Test
    fun `E2 logsizes counts each user's records`() {
        val account = Account(server)
        assertTrue(call("GET", "/admin/stats/logsizes").body().contains("\"${account.user}\":0"))
        Head.run("wire", server.wsUrl, account.user, account.pass, "0", "2", "2")
        assertTrue(call("GET", "/admin/stats/logsizes").body().contains("\"${account.user}\":2"))
    }

    @Test
    fun `E3 a PUT without a password fails and creates nobody`() {
        val user = Account(server).user + "-2"
        assertTrue(call("PUT", "/admin/users?username=$user").statusCode() in 400..599)
        assertFalse(user in users(), users())
    }
}
