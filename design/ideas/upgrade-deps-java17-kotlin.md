# Upgrade dependencies, Gradle, Java 17 minimum, newest Kotlin

Bring the toolchain and every dependency up to date: Gradle, Kotlin, and a Java 17 floor for
all modules. Today the build doesn't even start on a current JDK: Gradle 8.8 fails on JDK 25
with "What went wrong: 25.0.4".

Latest versions were looked up on 2026-10-18 via the `maven-versions` script. Re-run it before
acting on this; the table goes stale.

## Toolchain

| What | Now | Latest |
|---|---|---|
| Gradle wrapper | 8.8 | 9.8.1 |
| Kotlin plugin `org.jetbrains.kotlin.jvm` | 2.0.0 | 2.4.21 |
| `jvmTarget` / `sourceCompatibility` | 1.8 | 17 |
| CI image | `openjdk:11-jdk` (deprecated image) | a JDK 17+ image, e.g. `eclipse-temurin:21-jdk` (`Q_ci_image`) |

- Gradle 9 itself needs JVM 17+ to run, so the CI image has to change anyway.
- Regenerate the wrapper with `./gradlew wrapper --gradle-version 9.8.1`, per the `gradle`
  skill. Never hand-edit the jar.
- Use a Gradle toolchain (`kotlin { jvmToolchain(17) }`) rather than relying on whatever JDK
  runs Gradle.
- Gradle 9 removes old APIs. The root `ext["publish"]` lambda and the `subprojects { apply
  { plugin(...) } }` block may need rewriting; `buildSrc` convention plugins are the
  modern form (`Q_convention_plugins`).
- Bintray-era leftovers: the publish repo URL is `oss.sonatype.org`, but OSSRH was shut down
  in mid-2025 and publishing now goes through the Central Portal. Verify this before
  releasing.

## Libraries

| Library | Now | Latest | Note |
|---|---|---|---|
| slf4j-api / slf4j-simple | 2.0.13 | 2.0.20 | drop-in |
| gson | 2.11.0 | 2.14.0 | drop-in |
| vok-dataloader | 0.8 | 0.9.1 | an `api` dependency; its types are in UMN's API |
| h2-mvstore | 1.4.198 | 2.5.252 | **file format**, see below |
| okhttp | 3.12.2 | 5.5.0 | pinned for Android API < 21, see `Q_android_floor` |
| snakeyaml | 1.23 | 2.7 | 1.x has known CVEs; 2.x changed the `Yaml` constructor and safe-loading defaults |
| javalin | 3.11.0 | 7.2.3 | four majors; brings Jetty 12; admin REST API rewrite |
| jetty websocket-server | 9.4.12 | `jetty-websocket-jetty-server` 12.1.14 | renamed artifact, new packages, new API |
| mongodb-driver-sync | 3.8.0 | 5.13.0 | API changes in 4.x and 5.x |
| kafka-clients | 1.1.0 | 4.4.0 | three majors |
| hazelcast-client | 3.10.2 | `com.hazelcast:hazelcast` 5.7.0 | the client merged into the main artifact in 4.x; API changes |
| testcontainers mongodb / kafka | 1.19.8 | `testcontainers-mongodb` / `testcontainers-kafka` 2.0.5 | renamed artifacts in 2.x |
| lucene-core | 3.6.2 | 10.5.2 | only in `umn-performance-experiments`; maybe just delete the module (`Q_perf_module`) |
| dynatest | 0.25 | — | replaced by JUnit; see `drop-dynatest-for-junit6.md` |

Suggested order: toolchain first (Gradle, Kotlin, Java 17), then the drop-ins, then one
major-upgrade module at a time. Each step should leave `./gradlew build` green.

## Open questions

- `Q_android_floor`: Java 17 contradicts the **Runs on Android** promise and the "Kotlin on
  JVM 1.8" convention in `AGENTS.md`. Modern AGP (8+) desugars Java 17 bytecode, so this
  mainly means "a current Android toolchain". What should the promise become: a minimum
  AGP version, a minSdk, or both? Once the floor moves, the okhttp 3.12 and MVStore 1.4.198
  pins (both chosen for old Android) can go too. Aedict's own minSdk decides this.
- `Q_mvstore2`: MVStore 2.x likely can't open 1.4.x files (unverified; check the H2
  changelog and test it). If so, upgrading means a migration step for every existing
  database. That's a big compatibility break and belongs in
  `review-incompatibilities-since-0.15.md`; maybe stay on 1.4.x for 0.16.
- `Q_proxy_server_stack`: Javalin 7 + Jetty 12 is close to a rewrite of `UMNProxyServer`
  and `AdminServer`. Upgrade, or replace the stack with something smaller (plain Jetty 12
  for both the websocket and the admin endpoints)?
- `Q_backend_modules`: are the Kafka and Hazelcast backends still worth carrying? Neither
  runs on Android, Kafka isn't even published, and both need a three-major upgrade. Upgrade
  them, or drop them?
- `Q_ci_image`: which JDK image for CI? And should CI start running the tests (it currently
  runs `-x test`), now that Testcontainers needs Docker-in-Docker on GitLab?
- `Q_convention_plugins`: move the shared `subprojects {}` config to `buildSrc` convention
  plugins while touching it, or keep the change minimal?
- `Q_perf_module`: keep `umn-performance-experiments` (Lucene 3.6 comparison), or delete
  it?
