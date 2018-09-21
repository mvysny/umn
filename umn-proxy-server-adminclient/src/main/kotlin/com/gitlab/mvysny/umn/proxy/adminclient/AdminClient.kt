package com.gitlab.mvysny.umn.proxy.adminclient

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import retrofit2.Call
import retrofit2.CallAdapter
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.reflect.Type

interface AdminClient {
    @GET("users")
    @Throws(IOException::class, JsonParseException::class)
    fun getUsers(): List<String>

    @PUT("users")
    @Throws(IOException::class, JsonParseException::class)
    fun createOrUpdateUser(@Query("username") username: String, @Query("password") password: String): Unit?

    @DELETE("users/{username}")
    @Throws(IOException::class, JsonParseException::class)
    fun deleteUser(@Path("username") username: String): Unit?

    @GET("stats/logsizes")
    @Throws(IOException::class, JsonParseException::class)
    fun getLogSizes(): Map<String, Long>
}

private val gson = GsonBuilder()
    .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
    .create()

/**
 * Necessary so that Retrofit checks that Calls has been successfully executed.
 */
private object MyFactory : CallAdapter.Factory() {
    override fun get(returnType: Type, annotations: Array<Annotation>, retrofit: Retrofit): CallAdapter<*, *> {
        return object : CallAdapter<Any?, Any?> {
            override fun responseType(): Type = returnType

            override fun adapt(call: Call<Any?>): Any? {
                val result = call.execute()
                if (!result.isSuccessful) {
                    val msg = "${result.code()}: ${result.errorBody()?.string()}"
                    if (result.code() == 404) throw FileNotFoundException(msg)
                    throw IOException(msg)
                }
                return result.body()
            }
        }
    }
}

/**
 * Returns the Admin API for given endpoint url. Must end with a slash.
 * @param url for example `http://localhost:8081/admin/`
 */
fun createAdminClient(url: String): AdminClient {
    val retrofit = Retrofit.Builder()
        .baseUrl(url)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .addCallAdapterFactory(MyFactory)
        .build()
    return retrofit.create(AdminClient::class.java)
}
