# Publish `umn-proxy-server` to Maven Central, for embedding in tests

Brainstorm (the owner, 2026-11-07). It comes from Aedict Online's Vaadin 25 port
(`../aedict/aedict/design/ideas/aedict-web-accounts-dev-setup.md`): its Karibu tests and its dev
run want a real proxy server in-process, as an app's tests want an embedded H2, not a container
started by hand or a stubbed admin client.

## Today

- Maven Central has `umn-core`, `umn-proxy-client` and `umn-proxy-server-adminclient` 0.17, not
  the server: its `build.gradle.kts` has no `publish(…)` call, so it ships only as the Docker
  image (`mvysny/umn-proxy-server`) and the distribution zip inside it.
- The server already embeds in three lines; `MainTest` does it:
  `Main(Config(port, host, adminPort, proxy = Proxy(Standalone(dbDir)))).use { it.start() … }`.
  `Main.main()` adds only the config file, the shutdown hook and the endless loop.
- So a consumer has only the image (Testcontainers, a podman/docker daemon in every test run) or
  an `includeBuild("../../umn")` composite, which ties the two checkouts together.

## What publishing takes

- `publish("umn-proxy-server", "UMN: Proxy Server")` in `umn-proxy-server/build.gradle.kts`,
  released with 0.18; `AGENTS.md`'s module map ("Docker image") and a `CHANGELOG.md` line say so.
- **`explicitApi()`**, as the other library modules: the server becomes a library, so its public
  surface becomes a promise. Public: `Main`, `Config`, `Proxy`, `Standalone`, `Secure`; the rest
  (`UMNProxyServer`, `AdminServer`, the listeners, `GlobalLogClientProvider`?) `internal`.
- **The logging binding**: `slf4j-simple` is an `implementation` dependency today, so it would
  reach every consumer's classpath, and two bindings make SLF4J warn and pick one. It belongs to
  the application distribution only: `runtimeOnly` in a configuration the `application` plugin's
  start scripts and zip use, but the publication doesn't. (Aedict's port uses slf4j-simple
  2.0.20 too, so it wouldn't clash today; another consumer would.)
- **An embedding snippet** in `umn-proxy-server/README.md`, beside the Docker one.

## Open questions

- `Q_jetty_bom`: the server declares `api(platform(jetty-bom 12.1.14))`, so a consumer's Jetty is
  lifted to it: Aedict's port runs Vaadin Boot 13.7 on Jetty 12.1.8, so its test classpath would
  run 12.1.14 while the production zip runs 12.1.8. Harmless (a patch release), or make the BOM
  `implementation(platform(…))` so the server only pins its own build?
- `Q_ephemeral_ports`: `Config` takes fixed ports; `port = 0` binds an ephemeral one but nothing
  reads it back. Expose the bound ports from `Main` (Javalin's `port()`, the admin server's), so
  parallel test JVMs never clash? Not needed while a consumer's tests run in one JVM.
- `Q_main_name`: is `Main(cfg).start()` the embedding API, or does it get a nicer name
  (`UMNProxyServerApp`?) now that it is public? Renaming breaks the Docker image's `mainClass`
  only, which ships with the same release.
