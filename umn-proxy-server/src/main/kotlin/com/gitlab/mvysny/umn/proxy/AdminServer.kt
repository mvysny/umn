package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.sync.MVStoreGlobalLog
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.javalin.InternalServerErrorResponse
import io.javalin.Javalin
import io.javalin.core.util.HttpResponseExceptionMapper
import io.javalin.json.FromJsonMapper
import io.javalin.json.JavalinJson
import io.javalin.json.ToJsonMapper
import org.eclipse.jetty.server.Server
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.InetSocketAddress

class AdminServer(adr: InetSocketAddress) : Closeable {
    private val service: Javalin

    init {
        useGsonWithJavalin()
        service = Javalin.create()
                .disableStartupBanner()
                .server { Server(adr) }
                .apply { restAPI() }
                .start(adr.port)
    }

    private fun Javalin.restAPI() {
        get("/admin/users") { ctx -> ctx.json(mvstoreGlobalLog.listUsers()) }
        put("/admin/users") { ctx ->
            val username = ctx.validatedQueryParam("username").getOrThrow()
            val password = ctx.validatedQueryParam("password").getOrThrow()
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
        service.stop()
    }

    companion object {
        val mvstoreGlobalLog: MVStoreGlobalLog
            get() = (server.globalLogClientProvider as MVStoreGlobalLogProvider).mvstorelog
        @JvmStatic
        private val log = LoggerFactory.getLogger(AdminServer::class.java)
    }
}

fun useGsonWithJavalin() {
    GsonBuilder().create().applyToJavalin()
}
private fun Gson.applyToJavalin() {
    JavalinJson.fromJsonMapper = object : FromJsonMapper {
        override fun <T> map(json: String, targetClass: Class<T>): T = fromJson<T>(json, targetClass)
    }
    JavalinJson.toJsonMapper = object : ToJsonMapper {
        override fun map(obj: Any): String = toJson(obj)
    }
}
