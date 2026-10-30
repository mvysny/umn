package com.gitlab.mvysny.umn.proxy.adminclient

import com.google.gson.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AdminClient(val client: OkHttpClient, val baseUrl: String) {
    init {
        require(!baseUrl.endsWith("/")) { "$baseUrl must not end with a slash" }
    }

    fun getUsers(): List<String> {
        val request: Request = Request.Builder().get().url("$baseUrl/users").build()
        return client.exec(request) { responseBody -> responseBody.jsonArray(String::class.java) }
    }

    @Deprecated(
        "Sends the password in the URL, where logs pick it up. WARNING: createOrUpdateUser2 needs UMN proxy server 0.17+.",
        ReplaceWith("createOrUpdateUser2(username, password)")
    )
    fun createOrUpdateUser(username: String, password: String) {
        val url: HttpUrl = "$baseUrl/users".toHttpUrl().newBuilder()
                .addQueryParameter("username", username)
                .addQueryParameter("password", password)
                .build()
        val request = Request.Builder().put("".toRequestBody())
                .url(url)
                .build()
        client.exec(request) {}
    }

    /**
     * **Requires UMN proxy server 0.17+**: an older server fails the call (the 0.12 server with a 500) and creates
     * nobody.
     *
     * Creates the user, or updates its password if it exists. The credentials travel in the request body, never in
     * the URL. There's no fallback to [createOrUpdateUser]'s query parameters: that would put the password back in
     * the URL.
     * @throws java.io.IOException if the server rejects the request, e.g. an empty [password].
     */
    fun createOrUpdateUser2(username: String, password: String) {
        val json = JsonObject().apply {
            addProperty("username", username)
            addProperty("password", password)
        }
        val request = Request.Builder().put(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .url("$baseUrl/users")
                .build()
        client.exec(request) {}
    }

    fun deleteUser(username: String) {
        val url: HttpUrl = "$baseUrl/users".toHttpUrl().newBuilder()
                .addPathSegment(username)
                .build()
        val request = Request.Builder().delete()
                .url(url)
                .build()
        client.exec(request) {}
    }

    fun getLogSizes(): Map<String, Long> {
        val request: Request = Request.Builder().get().url("$baseUrl/stats/logsizes").build()
        return client.exec(request) {
            @Suppress("UNCHECKED_CAST")
            it.jsonMap(java.lang.Long::class.java) as Map<String, Long>
        }
    }
}
val okHttpClient = OkHttpClient()

/**
 * Returns the Admin API for given endpoint url. Must end with a slash.
 * @param url for example `http://localhost:10145/admin/`
 */
fun createAdminClient(url: String): AdminClient = AdminClient(okHttpClient, url.trim('/'))
