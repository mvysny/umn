# Publish `umn-proxy-server` to Maven Central, for embedding in tests

Parked (the owner, 2026-11-07): kept for a consumer that can't run Docker, or if Aedict changes
its mind. It came from Aedict Online's Vaadin 25 port
(`../aedict/aedict/design/ideas/aedict-web-accounts-dev-setup.md`), whose Karibu tests and dev
run want a real proxy server, not a container started by hand or a stubbed admin client. Aedict
then chose the alternative below, Testcontainers on the published image.

## The alternative: the Docker image through Testcontainers

What `umn-compat-0.15` already does (`src/test/kotlin/compat/Servers.kt`): a `GenericContainer`
on `mvysny/umn-proxy-server:<version>`, ports 10144 and 10145 exposed, waiting on
`GET /admin/users` on 10145, the consumer reading `getMappedPort`. Against embedding:

- **For**: the very artifact production runs, its JVM and `config.yaml` handling; nothing
  shared with the consumer's classpath (Jetty, slf4j, H2, Javalin, Kotlin), so the consumer's
  Jetty version stays its own (see "Accepted" below); random mapped ports,
  so parallel test JVMs never clash; nothing for UMN to publish or promise.
- **Against**: Docker (or podman's socket) running wherever the tests run; seconds to start a
  container; a consumer's dev data across restarts needs a bind mount and Testcontainers'
  reuse mode.

Embedding wins where Docker can't run (some CI runners, sandboxes) or start-up time matters.

## Today

- Maven Central has `umn-core`, `umn-proxy-client` and `umn-proxy-server-adminclient` 0.17, not
  the server: its `build.gradle.kts` has no `publish(…)` call, so it ships only as the Docker
  image (`mvysny/umn-proxy-server`) and the distribution zip inside it.
- The server already embeds in three lines; `MainTest` does it:
  `Main(Config(port, host, adminPort, proxy = Proxy(Standalone(dbDir)))).use { it.start() … }`.
  `Main.main()` adds only the config file, the shutdown hook and the endless loop.
- So a consumer has only the image (Testcontainers, above) or an `includeBuild("../../umn")`
  composite, which ties the two checkouts together.

## What publishing takes

- `publish("umn-proxy-server", "UMN: Proxy Server")` in `umn-proxy-server/build.gradle.kts`,
  released with 0.18; `AGENTS.md`'s module map ("Docker image") and a `CHANGELOG.md` line say so.
- **`explicitApi()`**, as the other library modules: the server becomes a library, so its public
  surface becomes a promise. Public: `Main`, `Config`, `Proxy`, `Standalone`, `Secure`; the rest
  (`UMNProxyServer`, `AdminServer`, the listeners, `GlobalLogClientProvider`?) `internal`.
- **Jetty and Javalin are implementation details**: the API is "listen on these ports", the HTTP
  stack is ours. `Secure.createJettyConnector(Server)`, the one public Jetty type today, becomes
  `internal`; `api(libs.javalin)` and both Jetty BOMs become `implementation`, off the consumer's
  compile classpath.
- **Accepted: a consumer's Jetty may be lifted.** One JVM holds one Jetty, and Gradle resolves the
  highest one asked for: `implementation` dependencies still reach the consumer's runtime variant,
  and Javalin 7.2.3 alone asks for 12.1.12. Aedict's port, on Vaadin Boot 13.7's Jetty 12.1.8,
  would have run 12.1.14 in its tests and 12.1.8 in production. Harmless within one minor; only
  shading would avoid it, not worth it.
- **The logging binding**: `slf4j-simple` is an `implementation` dependency today, so it would
  reach every consumer's classpath, and two bindings make SLF4J warn and pick one. It belongs to
  the application distribution only: `runtimeOnly` in a configuration the `application` plugin's
  start scripts and zip use, but the publication doesn't. (Aedict's port uses slf4j-simple
  2.0.20 too, so it wouldn't clash today; another consumer would.)
- **Ephemeral ports read back**: `Config` with `port = 0` / `adminPort = 0` already binds
  ephemeral ones, but nothing reads them back. `Main` gains `port` and `adminPort` getters, valid
  after `start()`, so parallel test JVMs never clash. They read the bound port, not `cfg`: the
  proxy server installs its own `ServerConnector`, so take that connector's `localPort` if
  Javalin's `port()` doesn't see it. `Main.main()`'s banner prints them instead of the configured
  ones.
- **An embedding snippet** in `umn-proxy-server/README.md`, beside the Docker one.

## Open questions

- `Q_main_name`: is `Main(cfg).start()` the embedding API, or does it get a nicer name
  (`UMNProxyServerApp`?) now that it is public? Renaming breaks the Docker image's `mainClass`
  only, which ships with the same release.
