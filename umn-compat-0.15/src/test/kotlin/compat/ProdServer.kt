package compat

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.util.UUID

/**
 * The proxy server Aedict-Online runs: the `mvysny/umn-proxy-server:0.12` image (UMN 0.13-SNAPSHOT on its own
 * Java 8), started once for all tests. Each test takes fresh [Account]s, so tests never see each other's logs.
 */
object ProdServer {
    // the 0.12 tag's digest, so a re-pushed tag can't change what the tests run against
    private val image = DockerImageName.parse("mvysny/umn-proxy-server@sha256:5bba1bda3e43fb11007ff082ee87a032f7a5c8601e0cb07844060019d364f99b")

    private val container = ProxyServerContainer(image)
        .withExposedPorts(10144, 10145)
        .waitingFor(Wait.forHttp("/admin/users").forPort(10145))
        .apply { start() }

    val wsUrl: String = "ws://${container.host}:${container.getMappedPort(10144)}"
    val adminUrl: String = "http://${container.host}:${container.getMappedPort(10145)}"
}

private class ProxyServerContainer(image: DockerImageName) : GenericContainer<ProxyServerContainer>(image)

/**
 * A user on [ProdServer], created the way Aedict-Online's registration creates one: a UUID name and password.
 */
class Account {
    val user: String = UUID.randomUUID().toString()
    val pass: String = UUID.randomUUID().toString()

    init {
        Server.createUser(ProdServer.adminUrl, user, pass)
    }

    val logSize: Long get() = Server.logSize(ProdServer.adminUrl, user)

    fun node(dir: Path, name: String): NodeFile = NodeFile(this, dir.resolve(name))
}
