# Upgrade dependencies, Gradle, Java 17 minimum, newest Kotlin

Bring every dependency up to date on top of the Java 17 floor.

Done on 2026-10-20: Gradle 9.8.1, Kotlin 2.4.21, `jvmTarget` / `sourceCompatibility` 17 and
`-Xjdk-release=17` (no toolchain), CI builds and tests on `eclipse-temurin` 17 and 21,
publishing through the Central Portal's OSSRH staging API (as vaadin-boot), proxy-server
Docker image `eclipse-temurin:21-jre`; slf4j 2.0.20,
gson 2.14.0, vok-dataloader 0.9.1, snakeyaml 2.7, and the proxy server on Javalin 7.2.3 with
Jetty 12.1.14 (websocket on `app.ws`, no direct Jetty dependency left).

Latest versions were looked up on 2026-10-18 via the `maven-versions` script. Re-run it before
acting on this; the table goes stale.

## Libraries

| Library | Now | Latest | Note |
|---|---|---|---|
| h2-mvstore | 1.4.198 | 2.5.252 | stays on 1.4.x (`D_frozen_0_15_formats`); a 1.4.x bump must keep format 1 and the `ObjectDataType` tags |
| okhttp | 3.12.2 | 5.5.0 | waits until Aedict is ready for a new major: it's `api` in `umn-proxy-client`. The Android pin is gone (minSdk 22; 3.13+ needs API 21); `umn-compat-0.15` reuses this version as 0.15's classpath, so give it its own 3.12.2 entry first |
| lucene-core | 3.6.2 | 10.5.2 | stays: only in `umn-performance-experiments`, which keeps its Lucene 3.6 comparison |
| proxy-server Docker base | `eclipse-temurin:21-jre` | 25 | v2 |

Suggested order: the drop-ins first, then one
major-upgrade module at a time. Each step should leave `./gradlew build` green.
