package compat

import com.gitlab.mvysny.umn.proxy.Config
import com.gitlab.mvysny.umn.proxy.Main
import com.gitlab.mvysny.umn.proxy.Proxy
import com.gitlab.mvysny.umn.proxy.Standalone
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * A proxy server the nodes sync through: [ProdServer] or [HeadServer]. Both start once for all tests; each test takes
 * fresh [Account]s, so tests never see each other's logs.
 */
interface ProxyServer {
    val wsUrl: String
    val adminUrl: String
}

/**
 * The proxy server Aedict-Online runs: the `mvysny/umn-proxy-server:0.12` image (UMN 0.13-SNAPSHOT on its own
 * Java 8). The nodes connect over plain `ws://` as Aedict-Online does; Android's TLS ends in the image's Jetty.
 *
 * Docker Hub could lose the image as JCenter lost the 0.15 jars: keep a `docker save` of it outside the repo.
 */
object ProdServer : ProxyServer {
    // the 0.12 tag's digest, so a re-pushed tag can't change what the tests run against
    private val image = DockerImageName.parse("mvysny/umn-proxy-server@sha256:5bba1bda3e43fb11007ff082ee87a032f7a5c8601e0cb07844060019d364f99b")

    private val container = ProxyServerContainer(image)
        .withExposedPorts(10144, 10145)
        .waitingFor(Wait.forHttp("/admin/users").forPort(10145))
        .apply { start() }

    override val wsUrl: String = "ws://${container.host}:${container.getMappedPort(10144)}"
    override val adminUrl: String = "http://${container.host}:${container.getMappedPort(10145)}"
}

private class ProxyServerContainer(image: DockerImageName) : GenericContainer<ProxyServerContainer>(image)

/**
 * HEAD's proxy server, in this JVM: what Aedict-Online's server becomes once upgraded.
 */
object HeadServer : ProxyServer {
    private val ports: List<Int> = List(2) { ServerSocket(0) }.map { it.use { socket -> socket.localPort } }

    init {
        val db = Files.createTempDirectory("umn-head-server").resolve("db")
        Main(Config(port = ports[0], host = "127.0.0.1", adminPort = ports[1], proxy = Proxy(Standalone(db.toString())))).start()
    }

    override val wsUrl: String = "ws://127.0.0.1:${ports[0]}"
    override val adminUrl: String = "http://127.0.0.1:${ports[1]}"
}

/**
 * A user on [server], created the way Aedict-Online's registration creates one: a UUID name and password.
 */
class Account(val server: ProxyServer) {
    val user: String = UUID.randomUUID().toString()
    val pass: String = UUID.randomUUID().toString()

    init {
        Server.createUser(server.adminUrl, user, pass)
    }

    val logSize: Long get() = Server.logSize(server.adminUrl, user)

    fun node(dir: Path, name: String): NodeFile = NodeFile(this, dir.resolve(name))
}
