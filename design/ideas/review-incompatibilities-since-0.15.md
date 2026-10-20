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
  client jar is in Aedict's `.m2`). HEAD client → 0.15 server can't be run here (no 0.15
  server build), so it rests on the static review.
- Keep the harness as a regression test before every release? Today it lives in a scratchpad.
- A wrapper new since 0.15 (e.g. `FullTextUMN`) misses whatever 0.15 writes in between: the
  `D_three_op_log` "no reindex" cost, now reachable by a downgrade/upgrade round-trip.

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
  them.

## 5. Distribution

- Bintray → Maven Central (`8e869fb`, `8272b03`). The groupId `com.gitlab.mvysny.umn` and
  the artifactIds look unchanged, but the README and the module READMEs still point at
  `dl.bintray.com` and `compile`, so they need updating.
- `umn-sync-kafka`, `umn-proxy-server` and `umn-sync-tester` weren't published in 0.15
  either (no `configureBintray` call at the tag), so nothing disappears for users.
  `umn-sync-tester` losing `Docker.kt` breaks no one.
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
