package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.sync.MVStoreGlobalLog
import io.javalin.Javalin
import io.javalin.config.RoutesConfig
import io.javalin.http.Context
import io.javalin.http.queryParamAsClass
import io.javalin.json.JavalinGson
import io.javalin.validation.ValidationException
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.InetSocketAddress

/**
 * Serves the REST admin API on [adr], over [globalLog]'s users and logs.
 */
class AdminServer(adr: InetSocketAddress, private val globalLog: MVStoreGlobalLog) : Closeable {
    private val javalin: Javalin = Javalin.create { config ->
        config.startup.showJavalinBanner = false
        config.jsonMapper(JavalinGson())
        config.routes.restAPI()
    }.start(adr.hostString, adr.port)

    private fun RoutesConfig.restAPI() {
        get("/admin/users") { ctx -> ctx.json(globalLog.listUsers()) }
        put("/admin/users") { ctx ->
            val username: String = ctx.requiredQueryParam("username")
            val password: String = ctx.requiredQueryParam("password")
            globalLog.createOrUpdateUser(username, password)
            ctx.status(204)
        }
        delete("/admin/users/{user}") { ctx ->
            globalLog.deleteUser(ctx.pathParam("user"))
            ctx.status(204)
        }
        get("/admin/stats/logsizes") { ctx ->
            ctx.json(globalLog.getLogSizes())
        }
        // Javalin's own handler renders the errors through JavalinGson, which can't serialize a ValidationError
        exception(ValidationException::class.java) { e, ctx ->
            ctx.status(400).result(e.errors.entries.joinToString { (param, errors) -> "Query parameter '$param' ${errors.joinToString { it.message }}" })
        }
        exception(Exception::class.java) { e, ctx ->
            log.error("Server error: $e", e)
            ctx.status(500).result(e.message ?: "Internal server error")
        }
    }

    // queryParamAsClass() alone lets an empty value through
    private fun Context.requiredQueryParam(name: String): String =
        queryParamAsClass<String>(name).check({ !it.isNullOrEmpty() }, "cannot be null or empty").get()

    override fun close() {
        javalin.stop()
    }

    companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(AdminServer::class.java)
    }
}
