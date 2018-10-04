package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.sync.MVStoreGlobalLog
import com.google.gson.GsonBuilder
import io.javalin.Context
import io.javalin.Javalin
import io.javalin.json.FromJsonMapper
import io.javalin.json.JavalinJson
import io.javalin.json.ToJsonMapper
import org.eclipse.jetty.server.Server
import java.io.Closeable
import java.net.InetSocketAddress

class AdminServer(adr: InetSocketAddress) : Closeable {
    private val service: Javalin

    init {
        val gson = GsonBuilder().create()
        JavalinJson.fromJsonMapper = object: FromJsonMapper {
            override fun <T> map(json: String, targetClass: Class<T>): T = gson.fromJson<T>(json, targetClass)
        }
        JavalinJson.toJsonMapper = object: ToJsonMapper {
            override fun map(obj: Any): String = gson.toJson(obj)
        }

        service = Javalin.create().server { Server(adr) }.start(adr.port)
        service.get("/admin/users") { ctx -> ctx.json(mvstoreGlobalLog.listUsers()) }
        service.put("/admin/users") { ctx ->
            mvstoreGlobalLog.createOrUpdateUser(ctx.validatedQueryParam("username").getOrThrow(), ctx.validatedQueryParam("password").getOrThrow())
            ctx.status(204)
        }
        service.delete("/admin/users/:user") { ctx ->
            mvstoreGlobalLog.deleteUser(ctx.pathParam("user"))
            ctx.status(204)
        }
        service.get("/admin/stats/logsizes") { ctx ->
            ctx.json(mvstoreGlobalLog.getLogSizes())
        }
    }

    override fun close() {
        service.stop()
    }

    companion object {
        val mvstoreGlobalLog: MVStoreGlobalLog
            get() = (server.globalLogClientProvider as MVStoreGlobalLogProvider).mvstorelog
    }
}
