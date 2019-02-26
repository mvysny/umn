package com.gitlab.mvysny.umn.proxy.adminclient

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.FileNotFoundException
import java.io.IOException
import java.io.Reader

/**
 * Destroys the [OkHttpClient] including the dispatcher, connection pool, everything. WARNING: THIS MAY AFFECT
 * OTHER http clients if they share e.g. dispatcher executor service.
 */
fun OkHttpClient.destroy() {
    dispatcher().executorService().shutdown()
    connectionPool().evictAll()
    cache()?.close()
}

/**
 * Fails if the response is not in 200..299 range; otherwise returns [this].
 */
fun Response.checkOk(): Response {
    if (!isSuccessful) {
        val msg = "${code()}: ${body()!!.string()} (${request().url()})"
        if (code() == 404) throw FileNotFoundException(msg)
        throw IOException(msg)
    }
    return this
}

private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

/**
 * Parses the response as a JSON and converts it to a Java object with given [clazz] using [gson].
 */
fun <T> ResponseBody.json(clazz: Class<T>): T = gson.fromJson(charStream(), clazz)

/**
 * Parses the response as a JSON array and converts it into a list of Java object with given [clazz] using [gson].
 */
fun <T> ResponseBody.jsonArray(clazz: Class<T>): List<T> = gson.fromJsonArray(charStream(), clazz)

/**
 * Parses the response as a JSON array and converts it into a list of Java object with given [clazz] using [gson].
 */
fun <V> ResponseBody.jsonMap(value: Class<V>): Map<String, V> = gson.fromJsonMap(charStream(), value)

/**
 * Parses [json] as a list of items with class [itemClass] and returns that.
 */
fun <T> Gson.fromJsonArray(json: String, itemClass: Class<T>): List<T> {
    val type = TypeToken.getParameterized(List::class.java, itemClass).type
    return fromJson<List<T>>(json, type)
}

/**
 * Parses [json] as a map of items with class [valueClass] and returns that.
 */
fun <T> Gson.fromJsonMap(json: String, valueClass: Class<T>): Map<String, T> {
    val type = TypeToken.getParameterized(Map::class.java, String::class.java, valueClass).type
    return fromJson<Map<String, T>>(json, type)
}

/**
 * Parses JSON from a [reader] as a list of items with class [itemClass] and returns that.
 */
fun <T> Gson.fromJsonArray(reader: Reader, itemClass: Class<T>): List<T> {
    val type = TypeToken.getParameterized(List::class.java, itemClass).type
    return fromJson<List<T>>(reader, type)
}

/**
 * Parses [json] as a map of items with class [valueClass] and returns that.
 */
fun <T> Gson.fromJsonMap(reader: Reader, valueClass: Class<T>): Map<String, T> {
    val type = TypeToken.getParameterized(Map::class.java, String::class.java, valueClass).type
    return fromJson<Map<String, T>>(reader, type)
}

/**
 * Runs given [request] synchronously and then runs [responseBlock] with the response body. The [Response] is properly closed afterwards.
 * Only calls the block on success; uses [checkOk] to check for failure prior calling the block.
 * @param responseBlock run on success.
 */
fun <T> OkHttpClient.exec(request: Request, responseBlock: (ResponseBody) -> T): T =
        newCall(request).execute().use {
            responseBlock(it.checkOk().body()!!)
        }
