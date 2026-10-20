# Upgrade dependencies, Gradle, Java 17 minimum, newest Kotlin

Bring every dependency up to date on top of the Java 17 floor.

Done on 2026-10-20: Gradle 9.8.1, Kotlin 2.4.21, `jvmTarget` / `sourceCompatibility` 17, CI
image `eclipse-temurin:21-jdk`, proxy-server Docker image `eclipse-temurin:21-jre`; slf4j 2.0.20,
gson 2.14.0, vok-dataloader 0.9.1, snakeyaml 2.7, and the proxy server on Javalin 7.2.3 with
Jetty 12.1.14 (websocket on `app.ws`, no direct Jetty dependency left).

Latest versions were looked up on 2026-10-18 via the `maven-versions` script. Re-run it before
acting on this; the table goes stale.

## Toolchain leftovers

- No Gradle toolchain (`kotlin { jvmToolchain(17) }`) yet: the build compiles with whatever
  JDK runs Gradle, and a toolchain would auto-download a JDK 17 where none is installed.
- The root `ext["publish"]` lambda and the `subprojects { apply { plugin(...) } }` block still
  work on Gradle 9; `buildSrc` convention plugins are the modern form (`Q_convention_plugins`).
- Bintray-era leftovers: the publish repo URL is `oss.sonatype.org`, but OSSRH was shut down
  in mid-2025 and publishing now goes through the Central Portal. Verify this before
  releasing.

## Libraries

| Library | Now | Latest | Note |
|---|---|---|---|
| h2-mvstore | 1.4.198 | 2.5.252 | stays on 1.4.x (`D_frozen_0_15_formats`); a 1.4.x bump must keep format 1 and the `ObjectDataType` tags |
| okhttp | 3.12.2 | 5.5.0 | waits until Aedict is ready for a new major: it's `api` in `umn-proxy-client`. The Android pin is gone (minSdk 22; 3.13+ needs API 21); `umn-compat-0.15` reuses this version as 0.15's classpath, so give it its own 3.12.2 entry first |
| lucene-core | 3.6.2 | 10.5.2 | only in `umn-performance-experiments`; maybe just delete the module (`Q_perf_module`) |

Suggested order: the drop-ins first, then one
major-upgrade module at a time. Each step should leave `./gradlew build` green.

## Open questions

- `Q_ci_image`: should CI start running the tests (it currently runs `-x test`)? With the
  Kafka and MongoDB modules gone, no test needs Docker any more.
- `Q_convention_plugins`: move the shared `subprojects {}` config to `buildSrc` convention
  plugins while touching it, or keep the change minimal?
- `Q_perf_module`: keep `umn-performance-experiments` (Lucene 3.6 comparison), or delete
  it?
