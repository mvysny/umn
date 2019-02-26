package com.gitlab.mvysny.umn.proxy.adminclient

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

class AdminClient(val client: OkHttpClient, val baseUrl: String) {
    init {
        require(!baseUrl.endsWith("/")) { "$baseUrl must not end with a slash" }
    }

    fun getUsers(): List<String> {
        val request = Request.Builder().get().url("$baseUrl/users").build()
        return client.exec(request) { responseBody -> responseBody.jsonArray(String::class.java) }
    }

    fun createOrUpdateUser(username: String, password: String) {
        val url: HttpUrl = HttpUrl.parse("$baseUrl/users")!!.newBuilder()
                .addQueryParameter("username", username)
                .addQueryParameter("password", password)
                .build()
        val request = Request.Builder().put(RequestBody.create(null, ""))
                .url(url)
                .build()
        client.exec(request) {}
    }

    fun deleteUser(username: String) {
        val url: HttpUrl = HttpUrl.parse("$baseUrl/users")!!.newBuilder()
                .addPathSegment(username)
                .build()
        val request = Request.Builder().delete()
                .url(url)
                .build()
        client.exec(request) {}
    }

    fun getLogSizes(): Map<String, Long> {
        val request = Request.Builder().get().url("$baseUrl/stats/logsizes").build()
        return client.exec(request) { it.jsonMap(java.lang.Long::class.java) as Map<String, Long> }
    }
}
val okHttpClient = OkHttpClient()

/**
 * Returns the Admin API for given endpoint url. Must end with a slash.
 * @param url for example `http://localhost:10145/admin/`
 */
fun createAdminClient(url: String): AdminClient = AdminClient(okHttpClient, url.trim('/'))
