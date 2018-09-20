package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.sync.MVStoreGlobalLog
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.DefaultServlet
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher
import java.io.Closeable
import javax.ws.rs.core.Application
import javax.ws.rs.core.MediaType
import java.net.InetSocketAddress
import javax.ws.rs.*

@Path("/users")
class UsersResource {

    val log: MVStoreGlobalLog get() = AdminServer.mvstoreGlobalLog

    @GET
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    fun getAllUsers(): List<String> = log.listUsers().toList()

    @PUT
    @Path("/")
    fun createUser(@QueryParam("username") username: String, @QueryParam("password") password: String) {
        log.createOrUpdateUser(username, password)
    }

    @DELETE
    @Path("/{username}")
    fun deleteUser(@PathParam("username") username: String) {
        log.deleteUser(username)
    }
}

class RestApiApplication : Application() {
    override fun getSingletons(): Set<Any> = setOf(UsersResource(), GsonMessageBodyHandler())
}

class AdminServer(val port: Int) : Closeable {
    private val server = Server(InetSocketAddress.createUnresolved("127.0.0.1", port))

    init {
        val context = ServletContextHandler(server, "/")
        val restEasyServlet = ServletHolder(HttpServletDispatcher())
        restEasyServlet.setInitParameter("resteasy.servlet.mapping.prefix", "/admin")
        restEasyServlet.setInitParameter("javax.ws.rs.Application", RestApiApplication::class.java.name)
        context.addServlet(restEasyServlet, "/admin/*")

        // Setup the DefaultServlet at "/".
        val defaultServlet = ServletHolder(DefaultServlet())
        context.addServlet(defaultServlet, "/")

        server.start()
    }

    override fun close() {
        server.stop()
    }

    companion object {
        val mvstoreGlobalLog: MVStoreGlobalLog
            get() = (server.globalLogClientProvider as MVStoreGlobalLogProvider).mvstorelog
    }
}
