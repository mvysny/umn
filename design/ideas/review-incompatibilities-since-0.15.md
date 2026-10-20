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

`umn-compat-0.15` (next section) has since covered `DocumentCreated2` and the proxy over a real
websocket against the deployed server: Docker Hub image `mvysny/umn-proxy-server:0.12`, whose
jars are `0.13-SNAPSHOT` (a build between `f90b105` 2018-09-27 and `f4ca711` 2018-10-02).
Not yet covered: a wrapper new since 0.15 (e.g. `FullTextUMN`) misses whatever 0.15 writes in
between, the `D_three_op_log` "no reindex" cost, now reachable by a downgrade/upgrade round-trip.

The deployed server vs 0.15 and HEAD, statically: `umn_proxy_log` storage is identical across
`f90b105..0.15` (same `users` / `{username}__log` maps, `PasswordHash`, MVStore 1.4.197), so an
in-place server upgrade opens the file. The wire differs only by 0.15's "`OnLogRecord` of size
0 = hole": the old server never sends one, but a server that punches holes would break pre-0.15
clients. `Started` follows `start()` in both. `0.15..HEAD` changes the server and `ProxyClient`
only by null-safety and `public`. The server never decodes a `LogRecord`; its whole contract is
the `username`/`password`/`rewind` headers, four message types and two close codes.

## The `umn-compat-0.15` test module

Implemented: A1–A3, B1–B4, C1–C5 and D pass. It guards the deployment as it will be: Aedict on
Android upgrades to UMN 0.16, while Aedict-Online stays as it is, both its server and its own
0.15 nodes. An unpublished module whose JUnit 6 tests run 0.15 nodes for real and the server as
the production Docker image.

### The deployment, from Aedict's sources

- **Aedict-Online is a 0.15 node like Android.** `aedict-online/…/userdata/SessionUserData.kt`
  builds `UserDataUMN` from `aedict-common` 3.50.19 (2020-09-09, UMN 0.15): a node file per
  user in `~/.aedict-online/mvstore/userdata/<userId>/db`, a volume kept across image rebuilds;
  one instance per user in an `ObjectPool`; its `Syncer` started with the user's session;
  `ProxyClient` over plain `ws://umn-proxy-server:10144`. Users edit there, so it writes too.
  Android connects to `wss://aedict-online.eu:10146`.
- **Users come from registration.** Aedict-Online's 0.15 `AdminClient` calls
  `createOrUpdateUser(user.id, umnProxyUserPass)`: `PUT /admin/users?username=…&password=…`
  on the 0.12 admin API, which is Spark, not Javalin (`f90b105`).
- **Aedict opens a node so** (`aedict-common/…/umn/UMNUtils.kt`): MVStore with `.compress()`
  and `autoCommitBufferSize(256)` → `MvstoreUMN` → `IndexingUMN` → `migrateV1V2` →
  `syncing(…, autoStart = false)`. `idxEntryRefKeyToCategory` indexes an item but returns its
  category; index keys such as `IdxCategoryOrdinal` are custom `Serializable` classes.
- **The field's 0.15 classpath isn't 0.15's pom.** Aedict excludes okhttp and pins 3.12.2, and
  pins vok-dataloader 0.5, on Android today and in Aedict-Online 3.50.19 alike. Kotlin stdlib
  is 1.4.0 in Aedict-Online, 2.4.21 on Android; MVStore 1.4.197 comes with umn-core.
- **Documents are small.** An `EntryRef` embeds its whole JMdict, Kanjidic or example entry:
  a few KB, tens for a heavy verb. `TagDoc` holds the user's text.
- **TLS doesn't change.** okhttp is 3.12.2 before and after the upgrade, and the image's Jetty
  terminates TLS, so the tests connect over `ws://` as Aedict-Online does.

### Actors

- **phone**: Android's node file, 0.15 → 0.16, never downgraded.
- **Aedict-Online**: a persistent 0.15 node; stays 0.15 as long as the server side does.
- **tablet**: an Android install that never updates, 0.15.
- **server**: the `mvysny/umn-proxy-server:0.12` container.

Every scenario ends with all nodes holding the same id → document map.

### Scenarios

**A. 0.16 client → 0.12 server**
1. Connect, get `Started`, push and get `OnAdded`, pull `OnLogRecord`.
2. A wrong password or unknown user closes with 4000, and `ProxyClient` reports it as 0.15 did.
3. Reconnecting with `rewind=N` gets only the records from N on.

**B. Phone upgrade**
1. Synced state carries over: the documents, every index, and the sync pointer, so nothing is
   pulled from 0 again.
2. The offline queue 0.15 left behind (create, update, delete) is pushed exactly once:
   `GET /admin/stats/logsizes` grows by exactly its length.
3. A phone that never synced turns sync on under 0.16 (`DocumentCreated2`); the others get it all.
4. 0.16 keeps working on the file it upgraded, across sessions.

**C. Mixed fleet, one account**
1. A 0.15 node pulls, decodes and indexes every record kind 0.16 writes: live, while its
   syncer is connected (an Aedict-Online session), and again after a reconnect.
2. 0.16 pulls what the 0.15 nodes write meanwhile.
3. Concurrent edits of one document on 0.15 and on 0.16 converge.
4. A fresh 0.16 node (a new phone, a reinstall) pulls the whole mixed log from `rewind=0`.
5. A fresh 0.15 node does the same: a user's first Aedict-Online session after syncing from
   Android.

**D. Golden bytes, no server:** the `LogRecord` and `AbstractMessage` bytes HEAD writes equal
bytes recorded from 0.15, and HEAD decodes 0.15's.

Deferred to v2: 0.15 reopening a node file 0.16 wrote. `D_frozen_0_15_formats` promises it,
Aedict never needs it. Dropped: a large document (sizes above; Jetty's 64 KB message default
lives in the unchanged server) and records sent but never ACKed before the upgrade (hard to
stage; four years in production without a complaint). Out of scope: Aedict-Online's admin
client and the server, both unchanged; hole punching, which the 0.12 server never does.

### How it's built

- **Old jars, committed.** `umn-core-0.15` and `umn-proxy-client-0.15` jars and poms in a
  module-local Maven repo, copied from Aedict's `.m2`: they died with JCenter. Their
  dependencies resolve from Maven Central into a `umn015` configuration of their own, forced
  to Aedict-Online's versions above, never mixed with HEAD's.
- **0.15 runs in a child JVM.** A `Driver015` in an `old` source set compiles against
  `umn015` only. A test runs HEAD in-process and forks the driver for each 0.15 step over the
  same files: no classloader isolation, no reflection.
- **One `Scenario.java`, compiled twice.** The steps compile against both 0.15 and HEAD, as the
  scratchpad harness did, so both versions run the same code. An intended API break in HEAD
  (surface 3) stops it compiling; then it splits into two copies.
- **The server via Testcontainers.** The image brings its own JRE (`openjdk:8u181`), so no
  server jars and no JDK question. `@Testcontainers(disabledWithoutDocker = true)` skips
  without Docker; GitLab CI runs `-x test` anyway. It pulls the image and Testcontainers'
  third-party reaper `testcontainers/ryuk`, or not with `TESTCONTAINERS_RYUK_DISABLED=true`.
  Docker Hub could lose the image as JCenter lost the jars: keep a `docker save` tarball
  outside the repo.
- **No fixed sleeps.** A sync step polls until the outgoing buffer drains and the server's log
  size is reached, with a timeout; the harness's `Thread.sleep(1000)` would flake in a build.
- **Checked to fail.** Changing `DocumentUpdated`'s type byte in HEAD fails 10 of the 16 tests.

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
  but check nothing in `ProxyClient` used a 3.13-only API. Moot for Aedict, which already
  ran 0.15 on its own pinned 3.12.2.
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
