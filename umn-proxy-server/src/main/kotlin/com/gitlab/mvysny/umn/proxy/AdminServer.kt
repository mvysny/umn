package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.sync.MVStoreGlobalLog
import com.google.gson.GsonBuilder
import spark.Request
import spark.Response
import spark.Service
import java.io.Closeable

class AdminServer(val port: Int) : Closeable {
    private val service: Service

    init {
        val gson = GsonBuilder().create()
        service = Service.ignite().port(port).ipAddress("127.0.0.1")
        service.get("/admin/users") { req, res -> gson.toJson(mvstoreGlobalLog.listUsers()) }
        service.put("/admin/users") { req, res ->
            mvstoreGlobalLog.createOrUpdateUser(req.queryParam("username"), req.queryParam("password"))
            res.return204NoContent()
        }
        service.delete("/admin/users/:user") { req, res ->
            mvstoreGlobalLog.deleteUser(req.params("user"))
            res.return204NoContent()
        }
        service.get("/admin/stats/logsizes") { req, res ->
            gson.toJson(mvstoreGlobalLog.getLogSizes())
        }
        service.awaitInitialization()
    }

    override fun close() {
        service.stop()
    }

    companion object {
        val mvstoreGlobalLog: MVStoreGlobalLog
            get() = (server.globalLogClientProvider as MVStoreGlobalLogProvider).mvstorelog
    }
}

fun Request.queryParam(name: String): String = requireNotNull(queryParams(name)) { "Query parameter $name is missing" }
fun Response.return204NoContent() {
    status(204)
    body("")
}