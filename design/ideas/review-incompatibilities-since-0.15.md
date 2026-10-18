# Review changes since 0.15 for incompatibilities

Before releasing 0.16, review everything since the `0.15` tag (2019-02-26) for changes that
break existing users: an app upgrading the library, a database file written by 0.15, a node
still on 0.15 syncing against the same global log. Scope: `git log 0.15..HEAD`, ~60 commits,
`git diff --stat 0.15..HEAD` touches 54 source/build files.

The question isn't "did the API change" alone. UMN has four compatibility surfaces, and Aedict
users have data on all of them.

## 1. On-disk format: can 0.16 open a 0.15 database, and the other way round?

- `78d88cf` "decrease umn db file size" changed `MvstoreUMN`. Did the stored document
  layout or the map names change?
- `760045c` "Allow specify data type for values": utility maps got a `valueType`. Do maps
  written with the default `ObjectDataType` still read back the same?
- MVStore 1.4.197 → 1.4.198 (`18db168`, issue #6). Is there a file-format change between
  them? Check the H2 changelog.
- `IndexingUMN` (+89/−) and `MutationLog` / `AckFIFOBuffer`: do the index maps, the
  `OUTGOING_BUFFER` and `CURRENT_LOG_PTR` keep their names and encodings? A silent change
  there loses unsynced changes, or re-pulls the whole log.
- `Q_downgrade`: do we promise downgrade at all, or only upgrade?

How to check it: write a db with the 0.15 jars (`git worktree add ../umn-0.15 0.15`),
then open it with HEAD. That needs Gradle running for both trees; see `Q_build_env`.

## 2. Wire formats: can 0.15 and 0.16 nodes share one global log?

- `LogRecord.toByteArray` / `fromByteArray` (`AnnouncingUMN.kt`, +37/−): any new record
  type or byte layout change? `DocumentCreated2`: was it there in 0.15?
- Proxy protocol, `umn-proxy-client` `Messages.kt`: the diff seen so far only adds
  `public`, but check the `TYPE_*` bytes and the field order.
- `MVStoreGlobalLog` storage (`users`, `{username}__log`, `properties`): a 0.16 proxy server
  started on a 0.15 server's db directory.
- `PasswordHash`: do hashes stored by a 0.15 server still verify?
- `Q_mixed_versions`: is a phone still on 0.15 against an upgraded proxy server a supported
  setup? For Aedict it will happen.

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

- `Q_build_env`: Gradle 8.8 doesn't start on the JDK 25 here, and 0.15 needs a Gradle /
  Kotlin 1.3 toolchain that likely wants JDK 8. Provide a JDK 17 (and an 8 for 0.15), or
  bump the wrapper first?
- `Q_findings_home`: where do the findings land? There's no CHANGELOG yet. A `CHANGELOG.md`
  with a 0.16 "Breaking:" section is the natural home for each incompatibility and its
  migration step. A format decision (e.g. "the db format is forward-only") goes into
  `design/decisions.md`.
- `Q_fix_or_document`: for each break found, fix it (a compat shim, a format migration) or
  document it and bump to 1.0 / 0.16 with a migration note?
