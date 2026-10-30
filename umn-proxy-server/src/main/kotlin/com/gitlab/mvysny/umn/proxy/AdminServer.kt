package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.sync.MVStoreGlobalLog
import com.google.gson.Gson
import com.google.gson.JsonParseException
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
            val (username, password) = if (ctx.queryParam("username") != null && ctx.queryParam("password") != null) {
                ctx.requiredQueryParam("username") to ctx.requiredQueryParam("password")
            } else {
                ctx.credentialsFromBody()
            }
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
        exception(InvalidBodyException::class.java) { e, ctx -> ctx.status(400).result(e.message!!) }
        exception(Exception::class.java) { e, ctx ->
            log.error("Server error: $e", e)
            ctx.status(500).result(e.message ?: "Internal server error")
        }
    }

    // queryParamAsClass() alone lets an empty value through
    private fun Context.requiredQueryParam(name: String): String =
        queryParamAsClass<String>(name).check({ !it.isNullOrEmpty() }, "cannot be null or empty").get()

    /**
     * Reads `{"username": "user", "password": "example"}` from the body, always as UTF-8, whatever the `Content-Type`:
     * with a query parameter missing the body is the only place left to look, and `curl -d` sends a form type.
     * @return the username and the password, both non-empty.
     * @throws InvalidBodyException if the body isn't such a JSON object.
     */
    private fun Context.credentialsFromBody(): Pair<String, String> {
        val credentials: Credentials? = try {
            gson.fromJson(bodyAsBytes().toString(Charsets.UTF_8), Credentials::class.java)
        } catch (e: JsonParseException) {
            throw InvalidBodyException("$CREDENTIALS_EXPECTED; ${e.message}")
        }
        val username: String? = credentials?.username
        val password: String? = credentials?.password
        // never echo the body: it holds the password
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) throw InvalidBodyException(CREDENTIALS_EXPECTED)
        return username to password
    }

    // nullable: Gson leaves a field missing from the JSON null, whatever Kotlin's type says
    private class Credentials(val username: String? = null, val password: String? = null)

    private class InvalidBodyException(message: String) : RuntimeException(message)

    override fun close() {
        javalin.stop()
    }

    companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(AdminServer::class.java)

        private val gson = Gson()

        private const val CREDENTIALS_EXPECTED =
            """Expected a JSON body {"username": "...", "password": "..."}, both non-empty strings"""
    }
}
