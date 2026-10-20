# Review changes since 0.15 for incompatibilities

Before releasing 0.16, review everything since the `0.15` tag (2019-02-26) for changes that
break existing users: an app upgrading the library, a database file written by 0.15, a node
still on 0.15 syncing against the same global log. Scope: `git log 0.15..HEAD`, ~60 commits,
`git diff --stat 0.15..HEAD` touches 54 source/build files.

The question isn't "did the API change" alone. UMN has four compatibility surfaces, and Aedict
users have data on all of them.

The rule is `D_frozen_0_15_formats`: surfaces 1 and 2 must not break in either direction, and
a break there is fixed, never documented. Surfaces 3–5 may break, with a migration note.

## 1. On-disk format and 2. wire formats — verified 2026-10-20, no break found

Statically: h2-mvstore 1.4.197 and 1.4.198 both write `FORMAT_WRITE = 1` / `FORMAT_READ = 1`
with identical `ObjectDataType` tags; every map name and its key/value types are unchanged;
`openMap(name, Builder())` with null key/value types is the same `ObjectDataType` default; the
`LogRecord` and proxy `AbstractMessage` type bytes and field order are unchanged (the diffs
only add `public`); `PasswordHash` and `UMNProxyServer` are untouched since 0.15.
`78d88cf` "decrease umn db file size" only adds an opt-in `MvstoreUMN.compact()`.

Empirically: a harness ran the published `umn-core-0.15.jar` (from Aedict-Reader's committed
`.m2`, on MVStore 1.4.197) and HEAD's jar (MVStore 1.4.198) in turns over the same node files
and one `MVStoreGlobalLog` file: 0.15 writes and queues unsynced changes → HEAD opens them,
the index finds them, HEAD pushes 0.15's queue → 0.15 opens HEAD's file and continues → and
again; fresh nodes of either version pull the mixed log, and each version decodes every
record. All passed, nothing pushed twice. The harness needs no Gradle, so 0.15 never has to be built.

Not yet covered:
- `DocumentCreated2`: the initial population of a node that had documents before it synced.
  Its bytes are unchanged, but the harness syncs from the start.
- The proxy over a real websocket. 0.15 `ProxyClient` → HEAD server can be run (the 0.15
  client jar is in Aedict's `.m2`). HEAD client → *deployed* server can be run after all:
  production runs Docker Hub image `mvysny/umn-proxy-server:0.12`, whose jars are
  `0.13-SNAPSHOT` (a build between `f90b105` 2018-09-27 and `f4ca711` 2018-10-02). Pull it,
  point `ProxyClient` and `AdminClient` at it.
- The deployed server vs 0.15, statically (`git diff f90b105 0.15`): `umn_proxy_log` storage
  is identical (same `users` / `{username}__log` maps, `PasswordHash`, MVStore 1.4.197), so an
  in-place server upgrade opens the file. The wire differs only by 0.15's "`OnLogRecord` of
  size 0 = hole": the old server never sends one, but a server that punches holes would break
  pre-0.15 clients. `Started` is now sent after `start()` succeeds; bytes and close codes are
  unchanged. The admin REST API isn't diffed yet (`f18cac3`, the Javalin bumps; see §4).
- A regression test, so this holds for every later release: see the next section.
- A wrapper new since 0.15 (e.g. `FullTextUMN`) misses whatever 0.15 writes in between: the
  `D_three_op_log` "no reindex" cost, now reachable by a downgrade/upgrade round-trip.

## The `umn-compat-0.15` test module — after `drop-dynatest-for-junit6.md`

Turn the scratchpad harness into an unpublished module whose plain JUnit 6 tests run the old
jars for real on every `./gradlew build`, with no Docker.

- **Old jars, committed.** `umn-core-0.15` and `umn-proxy-client-0.15` jars and poms in a
  module-local Maven repo, as Aedict does: they died with JCenter. Their dependencies (MVStore
  1.4.197, Kotlin 1.3, slf4j 1.7) resolve from Maven Central into a configuration of their own,
  never mixed with HEAD's.
- **Old code runs in a child JVM.** A small driver in its own source set compiles against that
  configuration only. A test runs HEAD in-process and forks the driver for each 0.15 step over
  the same files: no classloader isolation, no reflection.
- **Cases:**
  1. One node file, upgraded and downgraded in turns: 0.15 writes and queues unsynced
     changes, HEAD opens them, its index finds them, HEAD pushes 0.15's queue, and 0.15
     reopens HEAD's file and continues.
  2. Fresh nodes of each version pull a global log both versions wrote; nothing is pushed twice.
  3. `DocumentCreated2`: a node filled with sync off gets sync turned on, in both directions.
  4. A 0.15 `ProxyClient` over a real websocket through a HEAD `UMNProxyServer`.
  5. Golden bytes: the `LogRecord` and `AbstractMessage` bytes HEAD writes equal bytes
     recorded from 0.15. This catches a format slip without starting old code.
- **The deployed server.** For a HEAD `ProxyClient` and `AdminClient` against the 0.13-SNAPSHOT
  server, pull its jars out of `mvysny/umn-proxy-server:0.12` once, commit them alongside, and
  fork that server too. `Q_old_server_jars`: do the 2018 jars start on JDK 17+? If not, this
  case becomes an opt-in Docker test.
- When it lands, `AGENTS.md` names the module as the guard of `D_frozen_0_15_formats`, and
  "Not yet covered" above shrinks to whatever it can't reach.

## 3. Kotlin API and binary compatibility for library consumers

- Kotlin 1.3.21 → 2.0.0, `jvmTarget` 1.6 → 1.8. Does the Android toolchain that Aedict
  uses still accept the bytecode and the 2.0 stdlib metadata?
- `explicitApi()` (`45d7c30`..`69bb069`): did anything that was public in 0.15 become
  `internal` or `private`?
- Signature changes in `UMN.kt` (+93/−), `IndexingUMN.kt`, `Utils.kt`, `InMemoryUMN.kt`.
  Renames, new abstract members on `UMN`/`UtilityMap` (these break third-party
  implementations), changed defaults.
- `compile` → `api` / `implementation`: slf4j-api is now `implementation` in `umn-core`.
  A consumer that relied on getting it transitively no longer compiles.
- `Q_api_diff_tool`: diff the public API by hand, or let a tool do it? Options are
  JetBrains' binary-compatibility-validator Gradle plugin, japicmp on the 0.15 and HEAD
  jars, or reading the diff. A tool means a new dependency; check its version via the
  `maven-versions` skill.

## 4. Dependencies that users inherit

- vok-dataloader 0.4 → 0.8 is an `api` dependency of `umn-core`, and its types are in the
  UMN API (`keyLoader()` returns a `DataLoader`). Any break in vok-dataloader is a break in
  UMN.
- slf4j 1.7.25 → 2.0.13. 2.x finds its binding through `ServiceLoader`, so a consumer
  with a 1.7 binding (logback 1.2, slf4j-android) gets the NOP logger silently.
- okhttp 3.13.1 → 3.12.2. This is a downgrade for Android API < 21 and it's intended,
  but check nothing in `ProxyClient` used a 3.13-only API.
- MVStore 1.4.198 needs Android SDK 19+. The README says so; it should also go in the
  release notes.
- Javalin 2.6 → 3.11 in `umn-proxy-server` (`AdminServer.kt` +35/−): are the admin REST
  paths, status codes and JSON bodies unchanged? `umn-proxy-server-adminclient` depends on
  them, and Aedict-Online runs the 0.15 admin client against the 0.13-SNAPSHOT server.

## 5. Distribution

- Bintray → Maven Central (`8e869fb`, `8272b03`). The groupId `com.gitlab.mvysny.umn` and
  the artifactIds look unchanged, but the README and the module READMEs still point at
  `dl.bintray.com` and `compile`, so they need updating.
- `umn-sync-kafka`, `umn-proxy-server` and `umn-sync-tester` weren't published in 0.15
  either (no `configureBintray` call at the tag), so nothing disappears for users.
  `umn-sync-tester` losing `Docker.kt` breaks no one.
- `umn-sync-mongodb` and `umn-sync-hazelcast` *were* published up to 0.15 and are deleted now
  (unused, see `sync-over-third-party-log-stores.md`). Say so in the 0.16 release notes, or let
  them lapse silently (`Q_dropped_backends_note`)?
- `FullTextUMN` is new (`cabbe6f`..`dc05b77`). `7ad6b25` says "FullTextUMN probably a
  failure", so is it released as public API, marked experimental, or removed first?
  Shipping it public makes it a compatibility surface from 0.16 on.

## Open questions

- `Q_findings_home`: where do the findings land? There's no CHANGELOG yet. A `CHANGELOG.md`
  with a 0.16 "Breaking:" section is the natural home for each incompatibility and its
  migration step. A format decision (e.g. "the db format is forward-only") goes into
  `design/decisions.md`.
- `Q_fix_or_document`: an API break (surfaces 3–5): fix it or document it with a migration
  note? Disk and wire breaks are always fixed, per `D_frozen_0_15_formats`.
