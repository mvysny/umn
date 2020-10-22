package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.sync.MVStoreGlobalLog
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.javalin.Javalin
import io.javalin.http.HttpResponseExceptionMapper
import io.javalin.http.InternalServerErrorResponse
import io.javalin.plugin.json.FromJsonMapper
import io.javalin.plugin.json.JavalinJson
import io.javalin.plugin.json.ToJsonMapper
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.InetSocketAddress

class AdminServer(adr: InetSocketAddress) : Closeable {
    private val javalin: Javalin

    init {
        useGsonWithJavalin()
        javalin = Javalin.create { it.showJavalinBanner = false }
                .apply { restAPI() }
                .start(adr.hostName, adr.port)
    }

    private fun Javalin.restAPI() {
        get("/admin/users") { ctx -> ctx.json(mvstoreGlobalLog.listUsers()) }
        put("/admin/users") { ctx ->
            val username: String = ctx.queryParam<String>("username").get()
            val password: String = ctx.queryParam<String>("password").get()
            mvstoreGlobalLog.createOrUpdateUser(username, password)
            ctx.status(204)
        }
        delete("/admin/users/:user") { ctx ->
            mvstoreGlobalLog.deleteUser(ctx.pathParam("user"))
            ctx.status(204)
        }
        get("/admin/stats/logsizes") { ctx ->
            ctx.json(mvstoreGlobalLog.getLogSizes())
        }
        exception(Exception::class.java) { e, ctx ->
            log.error("Server error: $e", e)
            HttpResponseExceptionMapper.handle(InternalServerErrorResponse(e.message ?: "Internal server error"), ctx)
        }
    }

    override fun close() {
        javalin.stop()
    }

    companion object {
        val mvstoreGlobalLog: MVStoreGlobalLog
            get() = (server.globalLogClientProvider as MVStoreGlobalLogProvider).mvstorelog
        @JvmStatic
        private val log = LoggerFactory.getLogger(AdminServer::class.java)
    }
}

fun useGsonWithJavalin() {
    GsonBuilder().create().configureToJavalin()
}
/**
 * Configures Gson to Javalin. You need to call this if you wish to produce JSON
 * via Javalin's [Context.json], or parse incoming JSON via [Context.bodyAsClass].
 */
private fun Gson.configureToJavalin() {
    JavalinJson.fromJsonMapper = object : FromJsonMapper {
        override fun <T> map(json: String, targetClass: Class<T>): T = fromJson(json, targetClass)
    }
    JavalinJson.toJsonMapper = object : ToJsonMapper {
        override fun map(obj: Any): String = toJson(obj)
    }
}
