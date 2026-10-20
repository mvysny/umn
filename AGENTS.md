# U.M.N. — AGENTS.md

## What this is

The U.M.N. is a NoSQL data storage designed to have the following properties:
* The basic storage interface only offers a very simple key-value storage capabilities
* Designed to be enhanced easily with indices and synchronization capabilities
* Blazingly fast - [H2's MVStore](https://www.h2database.com/html/mvstore.html) storage is 10-100x faster than sqlite3 or any other SQL database.

## Promises

- **Runs on Android, minSdk 22 (5.1)**, Aedict's floor. `umn-core` and `umn-proxy-client` target Java 17 bytecode, which Android Gradle Plugin 8+ desugars, and keep to dependencies API 22 can load.
- **A dumb core.** The storage is a UUID → blob map with three mutations; every other capability is a wrapper around it. See `D_three_op_log`.
- **Sync over any server.** All sync logic is client-side; a new backend is a thin `GlobalLogClient`, nothing server-side.
- **0.15-compatible on disk and on the wire, both ways.** Every later release opens 0.15 files and writes files 0.15 opens, and syncs with 0.15 nodes and proxy servers; only the API may change. See `D_frozen_0_15_formats`.

## Design docs

| File | Owns | Loaded |
|---|---|---|
| `README.md`, module `README.md`s | the pitch, usage, the user-facing sync and proxy-server docs | — |
| `AGENTS.md` (this) | promises, invariants, the module map, conventions, commands | every turn |
| `design/decisions.md` | why this and not that — `D_` entries, FAQ-shaped | lazy |
| KDoc | what one symbol does and why it is shaped so | at the symbol |

Every fact lives in exactly one of these; the others link to it. `design/ideas/` holds ideas not
yet acted on, one per file; an idea is deleted once implemented or rejected, its lasting facts
moved to the files above first.

## Invariants

- **Mutate through the outermost wrapper only.** A write to a delegate bypasses the wrappers above it — indices go stale, the sync log misses the change.
- **Applying a `LogRecord` is idempotent.** The pusher re-sends unACKed records and every node replays its own records back.
- **Utility maps are node-local, never synced.** Indices, the mutation log and the log pointer live there; each node rebuilds its own.
- **A `GlobalLogClient` announces records in global-log order, append-only.** Nodes replaying a different order diverge.
- **One `Syncer` per UMN storage.** Two share the outgoing buffer unsafely and double-send every change.

## Module map

- `umn-core` — the `UMN` interface, MVStore and in-memory stores, indexing, full-text, the sync engine.
- `umn-sync-tester` — shared test batteries every `GlobalLogClient` implementation runs.
- `umn-proxy-client` — websocket `GlobalLogClient` for Android, okhttp only.
- `umn-proxy-server` — standalone websocket proxy server with an MVStore global log and REST admin; Docker image.
- `umn-proxy-server-adminclient` — Kotlin client for the proxy server's REST admin API.
- `umn-performance-experiments` — ad-hoc benchmarks, e.g. full-text against Lucene; unpublished.
- `umn-compat-0.15` — guards `D_frozen_0_15_formats`: forked UMN 0.15 nodes and HEAD sync through the production proxy-server image (Testcontainers, skipped without Docker) and through HEAD's, in-process; unpublished.

## Conventions

- **Kotlin targeting Java 17, Gradle 9 Kotlin DSL.** `kotlin("stdlib")`, never `-jdk8`, in anything Android loads.
- **Library modules use `explicitApi()`**; every public declaration states its visibility.
- **Tests: JUnit 6 Jupiter with `kotlin.test` assertions**; a reusable battery is an abstract class, run as a `@Nested inner class` subclass; every `GlobalLogClient` via the `umn-sync-tester` batteries.
- **Logging through slf4j-api**; `slf4j-simple` only in the proxy server and tests.
- **Dependencies live in `gradle/libs.versions.toml`**; an Android-forced pin carries its reason as a comment there.
- **Release: Maven Central** through the root build's `publish(artifactId, description)`; a module without the call stays unpublished.

## Commands

- `./gradlew build` — compile and test everything.
- `./gradlew umn-core:test` — one module's tests.
- `./gradlew koverHtmlReport` — line coverage of the shipped modules, merged, in `build/reports/kover/html/`.
- `./gradlew build -x test -x verifyDesignTripwires` and `./gradlew clean umn-proxy-client:build -x test` — what GitLab CI runs on push (`.gitlab-ci.yml`).
- `./gradlew check` also runs `design/verify_design_tripwires.sh`; CI runs it as a job of its own.
- `./gradlew umn-proxy-server:build -x test` — the proxy server zip, in `umn-proxy-server/build/distributions/`.

## Maintenance of this file

Loaded every turn; cap 34 KB, a module's own `AGENTS.md` 10 KB. Over it, in this order:
delete what has no home — status, history, class lists, what the code already says; trim
each line to its fact plus one clause and send the explanation home — why →
`design/decisions.md`, how across symbols → `design/architecture.md`, how in one symbol →
its doc comment, what upstream does → `design/research.md`; only then a module's own
`AGENTS.md`, peripheral modules first, never the core. Never paraphrase a lazy entry into a
line here. `design/verify_design_tripwires.sh` checks the caps and the cites.
