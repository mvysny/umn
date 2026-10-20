package compat

import com.gitlab.mvysny.umn.proxy.Config
import com.gitlab.mvysny.umn.proxy.Main
import com.gitlab.mvysny.umn.proxy.Proxy
import com.gitlab.mvysny.umn.proxy.Standalone
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.Closeable
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * A proxy server the nodes sync through: [ProdServer] or [HeadServer]. The `shared` ones start once for all tests;
 * each test takes fresh [Account]s, so tests never see each other's logs.
 */
interface ProxyServer {
    val wsUrl: String
    val adminUrl: String
}

/**
 * The proxy server Aedict-Online runs: the `mvysny/umn-proxy-server:0.12` image (UMN 0.13-SNAPSHOT on its own
 * Java 8), in a container of its own. The nodes connect over plain `ws://` as Aedict-Online does; Android's TLS ends
 * in the image's Jetty.
 *
 * Docker Hub could lose the image as JCenter lost the 0.15 jars: keep a `docker save` of it outside the repo.
 */
class ProdServer : ProxyServer {
    private val container = ProxyServerContainer(image)
        .withExposedPorts(10144, 10145)
        .waitingFor(Wait.forHttp("/admin/users").forPort(10145))
        .apply { start() }

    override val wsUrl: String = "ws://${container.host}:${container.getMappedPort(10144)}"
    override val adminUrl: String = "http://${container.host}:${container.getMappedPort(10145)}"

    /**
     * Stops the server as `docker stop` does, so it closes its database, then copies the database into [dbDir] and
     * removes the container.
     */
    fun stopInto(dbDir: Path) {
        container.dockerClient.stopContainerCmd(container.containerId).exec()
        Files.createDirectories(dbDir)
        container.copyFileFromContainer("/var/umn-proxy-server/db/umn_proxy_log", dbDir.resolve("umn_proxy_log").toString())
        container.stop()
    }

    companion object {
        // the 0.12 tag's digest, so a re-pushed tag can't change what the tests run against
        private val image = DockerImageName.parse("mvysny/umn-proxy-server@sha256:5bba1bda3e43fb11007ff082ee87a032f7a5c8601e0cb07844060019d364f99b")

        val shared: ProdServer by lazy { ProdServer() }
    }
}

private class ProxyServerContainer(image: DockerImageName) : GenericContainer<ProxyServerContainer>(image)

/**
 * HEAD's proxy server, in this JVM, on the database in [dbDir]: what Aedict-Online's server becomes once upgraded.
 */
class HeadServer(dbDir: Path) : ProxyServer, Closeable {
    private val ports: List<Int> = List(2) { ServerSocket(0) }.map { it.use { socket -> socket.localPort } }
    private val main = Main(Config(port = ports[0], host = "127.0.0.1", adminPort = ports[1], proxy = Proxy(Standalone(dbDir.toString()))))
        .apply { start() }

    override val wsUrl: String = "ws://127.0.0.1:${ports[0]}"
    override val adminUrl: String = "http://127.0.0.1:${ports[1]}"

    override fun close() {
        main.close()
    }

    companion object {
        val shared: HeadServer by lazy { HeadServer(Files.createTempDirectory("umn-head-server").resolve("db")) }
    }
}

/**
 * A user on [server], created the way Aedict-Online's registration creates one: a UUID name and password.
 */
class Account private constructor(val server: ProxyServer, val user: String, val pass: String) {
    constructor(server: ProxyServer) : this(server, UUID.randomUUID().toString(), UUID.randomUUID().toString()) {
        Server.createUser(server.adminUrl, user, pass)
    }

    val logSize: Long get() = Server.logSize(server.adminUrl, user)

    fun node(dir: Path, name: String): NodeFile = NodeFile(this, dir.resolve(name))

    /**
     * This account on [server], which took over this one's database.
     */
    fun movedTo(server: ProxyServer): Account = Account(server, user, pass)
}
