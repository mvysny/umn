package com.gitlab.mvysny.umn.proxy.adminclient

import com.google.gson.Gson
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.junit.jupiter.api.Test
import kotlin.test.expect

class OkHttpUtilsTest {
    private data class Person(val firstName: String? = null)

    @Test fun `json uses lower case with underscores`() {
        val body = ResponseBody.create(MediaType.parse("application/json"), """{"first_name": "Duke"}""")
        expect(Person("Duke")) { body.json(Person::class.java) }
    }

    @Test fun `parse JSON strings`() {
        expect(listOf("a", "b")) { Gson().fromJsonArray("""["a", "b"]""", String::class.java) }
        expect(mapOf("a" to 1L)) { Gson().fromJsonMap("""{"a": 1}""", java.lang.Long::class.java).mapValues { it.value.toLong() } }
    }

    @Test fun `destroy shuts the client down`() {
        val client = OkHttpClient()
        client.destroy()
        expect(true) { client.dispatcher().executorService().isShutdown }
    }
}
