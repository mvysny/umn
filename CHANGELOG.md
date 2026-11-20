# Changelog

What changes for a user of each release, newest first. A `Breaking:` line carries its migration
step; the wire format never breaks, and a release opens every file an earlier one wrote
(`D_frozen_0_15_formats`). Releases before 0.16 have no entries.

## 0.19 — unreleased

- The project moved from GitLab to GitHub: https://github.com/mvysny/umn. The groupId
  `com.gitlab.mvysny.umn` and the package names stay as they are.
- `UMN.get(id, Long::class)` and `find()` accept a Kotlin primitive class, and an `IndexDef`'s
  document type may be one. In 0.18, `get`/`find` threw `ClassCastException` and the index ignored
  every document; only `java.lang.Long::class` worked.
- `IndexingUMN` clears an index no longer passed without reading its keys, so the old index's key
  class may now be deleted, renamed or obfuscated once the index has a new name. In 0.18, the
  open threw `ClassNotFoundException` once the class was gone, and the app couldn't open its
  database. A clear that fails is now logged and retried on the next open, not fatal.
- Dependencies: h2-mvstore 1.4.200, the last 1.4.x, still file format 1. A store whose last write
  a kill cut short now opens at its last whole commit. 1.4.198 opened it empty whenever the store
  header named no chunk yet, as in a new node's store and in the proxy server's log after every
  start, and its close then truncated the file: a node lost its unsynced edits, the proxy server
  its whole log.
- **Breaking:** a proxy server upgrades only forward. 0.19 closes its log with H2's clean-shutdown
  flag, which 0.18 and older keep while they write, so 0.19 started again on a log an older
  server wrote meanwhile opens it silently missing those records. Migration: fix a bad release
  with a newer one, never by starting the older image; see the server's README.
- **Breaking:** for code calling MVStore itself, 1.4.200 drops `compactRewriteFully()` and
  `getCurrentFillRate()`. Migration: drop the first, as MVStore's auto-compaction keeps the file
  small; not 1.4.200's `compactFile(Long.MAX_VALUE)`, which never returns on a store whose data
  is live. And `getChunksFillRate()` for the second.
- **Breaking:** `MvstoreUMN.compact()` is gone: MVStore's auto-compaction already keeps the file
  small. Migration: drop the call, and open the store with the default `autoCompactFillRate`.
- **Breaking:** `UMN.dropUtilityMap(name)` frees a utility map's entries and forgets the map,
  reading none of its keys or values. Migration: a `UMN` implemented outside this library, such as
  a wrapper, implements it, typically as `delegate.dropUtilityMap(name)`.

## 0.18 — 2026-11-08

- **Breaking:** `IndexImpl.keyLoader()` and `KeyLoader` are gone, and with them the vok-dataloader
  dependency. In their place, `IndexImpl.keyPagedList(pageSize, descending, dataMissingMarker)`, a
  `List` that loads an index's keys a page at a time, and in either order, for a UI list; and
  `keyCount()` and `keyPage(indices, descending)` to page by hand. Unlike `PageFetchingList`, the
  list's size is fixed when it's created, so an Android adapter never sees it change unannounced.
  Migration: replace `PageFetchingList(index.keyLoader(), pageSize, marker)` with
  `index.keyPagedList(pageSize, dataMissingMarker = marker)`; code that used vok-dataloader for
  anything else declares it itself.
- `IndexingUMN` builds a new index over the documents already stored, when it's constructed: that
  one open walks the whole database, blocking. An index is built once and never rebuilt, so a
  changed `keyGetter` or key type takes a new index name; an index no longer passed has its entries
  cleared. A document whose keys throw during the build is logged and left out. Before, a new index
  started empty and stayed blind to every existing document. Indices from earlier releases are
  taken as built, holes included, unless still empty.
- `CompositeKey`: an index key of several parts, ordered part by part, e.g.
  `CompositeKey.of(categoryId, ordinal)`; `For.prefix(CompositeKey.of(categoryId))` finds one
  category's documents in order. MVStore stores it natively, with no class name in the file, so
  prefer it to a custom `Serializable` key class, which breaks the database once renamed, moved or
  obfuscated. Custom key classes keep working.
- `findLastDoc` and `findLastID` given a `For.KeyRange` no longer return a document whose key lies
  below the range: when the range held no key, they returned the document under the next lower
  key, e.g. the last item of a neighbouring category.
- `InMemoryUMN` is deprecated in favour of `MvstoreUMN.inMemory(serializer, typeToClassResolver)`,
  an MVStore held in memory. `InMemoryUMN` kept live objects, so a document or an index key that
  doesn't serialize passed its tests and failed in production, and its utility maps returned keys
  unordered. Migration: replace `InMemoryUMN(resolver, serializer)` with
  `MvstoreUMN.inMemory(serializer, resolver)`; the IDE's quick-fix does it.
- `IndexingUMN` runs every index's `keyGetter` before it writes the document, so one that throws
  fails the `create`, `update` or `delete` and writes nothing. Before, the document was written and
  left unindexed for good. `valueIdGetter` still runs after the write: keep it from throwing.
- A record from another node that fails to apply, e.g. a document class an older app version can't
  deserialize, stalls syncing instead of being skipped. The `Syncer` reports
  `SyncerStatus.Offline` with a `LogRecordNotAppliedException` as the cause and retries the record
  with a growing back-off; once an app update makes it apply, syncing catches up in order. Before,
  the in-process clients skipped the record for good, and `umn-proxy-client` reconnected every
  second with no cause. Changes from this node barely go out during a stall either: show the
  cause, e.g. as "update the app to keep syncing".

- `umn-proxy-server` is published to Maven Central, to embed the server in a test or a dev run
  with no Docker: `Main(config).use { it.start() … }`. `port = 0` and `adminPort = 0` bind free
  ports, which `Main.port` and `Main.adminPort` read back; see its README. It lifts the app's own
  Jetty to its 12.1.x, one JVM holding one Jetty.
- The proxy wire messages (`AbstractMessage`) move from `umn-proxy-client` to the new
  `umn-proxy-protocol` artifact, in the same package. `umn-proxy-client` depends on it, so apps
  change nothing; the proxy server depends on it instead of the client, and brings no okhttp.
- `SerializedLogRecord` is deprecated: it was only ever `ByteArray`, a `LogRecord`'s bytes, no Java
  serialization, and the alias hid that. `GlobalLogClient` and the proxy messages say `ByteArray`
  now; binaries are unaffected. Migration: replace `SerializedLogRecord` with `ByteArray`.
- The proxy server no longer burns CPU while idle: MVStore's auto-compaction walked every chunk of the
  log ten times a second, 7.6 % of a one-CPU box on a 4 GB log. It now compacts once a minute.
- Deleting a user (`MVStoreGlobalLog.deleteUser()`, the admin API's `DELETE`) now closes the user's
  started clients and frees their log in the file; before, the log stayed there for good. The proxy
  server disconnects the user's devices with close code 4000, so they fail as on a wrong password.
  `MVStoreGlobalLogClient.setUserDeletedCallback()` is the hook for that.
- The proxy server compacts its log on every start: it rewrites `umn_proxy_log` into a copy holding
  only live data, which also reclaims the space deleted users leaked before. Production's 3.8 GB log
  became 755 MB, in 20 s. The start takes that much longer and needs free disk the size of the live
  data, else it's skipped with a warning; `compactDatabaseOnStartup: false` under `standalone:`
  turns it off. Mount the `db` directory in Docker, not the file alone. Space already lost comes back only from an offline
  `MVStoreTool.compact()` of the file, on a copy first, during a planned stop.
- The proxy server logs a connection's `username`, `rewind`, `User-Agent` and `Host` headers, no
  longer all of them but `password`: behind a reverse proxy, `X-Forwarded-For` put each client's IP
  address beside its user ID in the log.

## 0.17 — 2026-11-04

- **Breaking:** okhttp 4.12.0 in `umn-proxy-client` (0.16: 3.12.2), an `api` dependency; Gradle
  lifts an app's own 3.x pin to it. It is binary compatible with 3.x and Java code compiles
  unchanged, but Kotlin code calling okhttp fails on its getters turned properties. Migration: in
  Kotlin, `response.body()` → `response.body`, `HttpUrl.parse(s)` → `s.toHttpUrlOrNull()`,
  `RequestBody.create(type, x)` → `x.toRequestBody(type)`; okhttp's
  [upgrade guide](https://square.github.io/okhttp/changelogs/upgrading_to_okhttp_4/) lists the rest.
- **Breaking:** the proxy server's plain ws (`host:`) listens on `127.0.0.1` by default (0.16:
  `0.0.0.0`), since it sends the passwords unencrypted; a `config.yaml` with no `host:` stops serving
  remote clients. The Docker image's bundled `config.yaml` keeps `host: 0.0.0.0`, and its docs and
  `docker-compose.yml` publish 10144 as `127.0.0.1:10144:10144`; but a `config.yaml` of your own
  mounted into the container with no `host:` loses plain ws altogether, since no `-p` reaches the
  container's own localhost. Migration: remote clients connect over wss (Caddy in front, or
  `secure:`) instead; `host: 0.0.0.0` restores the old bind, and a container's own `config.yaml`
  needs it.
- With a `secure:` stanza, the proxy server serves plain ws beside wss again, as 0.15 did, so
  local clients can keep `ws://localhost:10144`.
- The proxy server's `secure:` stanza takes `keystoreReloadSeconds: N`: the server checks the
  keystore every N seconds and serves a renewed certificate to new connections, with no restart and
  no dropped connection. Off by default. In Docker, mount the keystore's directory, not the file
  alone, or the container never sees the renewed one.
- The proxy server's `PUT /admin/users` takes the credentials as a JSON body,
  `{"username": "...", "password": "..."}`, so the password stays out of URLs and logs. The query
  params still work when both are present.
- `AdminClient.createOrUpdateUser2` sends that JSON body and **needs a 0.17+ proxy server**; an
  older one fails it (the 0.12 server with a 500) and creates nobody. `createOrUpdateUser` is
  deprecated. Migration: upgrade the server first,
  then switch the admin client to `createOrUpdateUser2`.
- `ProxyClient` sends a websocket ping every minute on its own, so an idle connection no longer
  drops every 5 minutes when the app's `OkHttpClient` has no `pingInterval`. The new
  `pingIntervalSeconds` parameter changes it, 0 turns pings off, and it overrides the
  `OkHttpClient`'s own interval.
- The proxy server no longer logs a connecting client's `password` header; 0.16 logged it in
  plain text with the other headers, at INFO.
- The proxy server's Docker image is on Docker Hub again, `mvysny/umn-proxy-server:0.17` (the last
  one was 0.12), for amd64 and arm64.
- Docs: the proxy server's README shows Caddy in front of plain ws serving wss, with a Let's
  Encrypt certificate Caddy renews by itself, no keystore and no `secure:` stanza.
  `umn-proxy-client`'s README shows how an app on Android below 7.1.1 trusts Let's Encrypt's root;
  without it the syncer stays offline with "Trust anchor for certification path not found".

## 0.16 — 2026-10-21

- 0.16 opens database files 0.15 wrote, and syncs with 0.15 nodes through the same proxy server,
  in both directions: the disk and wire formats are unchanged.
- The proxy server upgrades in place: it opens the 0.12 server's database (`umn_proxy_log`) as
  is, users, passwords and logs included.
- **Breaking:** with a `secure:` stanza, the proxy server serves wss only and ignores `port`/`host`
  (0.15 served plain ws beside it). Migration: point every client at `wss://…:10146`.
- **Breaking:** needs Android 5.1 (API 22): UMN is now Java 17 bytecode, which Android Gradle
  Plugin 8+ desugars. Migration: `minSdk = 22` or higher, and AGP 8 or newer.
- **Breaking:** built with Kotlin 2.4 (0.15: 1.3). Migration: compile a Kotlin app with Kotlin 2.3
  or newer, which reads 2.4's metadata.
- **Breaking:** slf4j-api 2.0 (0.15: 1.7), and `umn-core` no longer puts it on your compile
  classpath. slf4j 2 ignores a 1.7 binding and logs nothing. Migration: declare `slf4j-api`
  yourself if you call it, and use a 2.x binding, e.g. `slf4j-simple` 2.x or logback 1.3+.
- **Breaking:** published to Maven Central; 0.15 was on Bintray, which is gone. The groupId
  `com.gitlab.mvysny.umn` and the artifactIds are unchanged. Migration: `mavenCentral()` in your
  repositories.
- **Breaking:** `UMN.openUtilityMap()` takes optional `keyType` and `valueType` arguments, so
  callers compile unchanged, but a class implementing `UMN` must override the new signature;
  `MvstoreUtilityMap`'s constructor takes them without defaults. Migration: add the two
  `DataType?` parameters to an override and pass them on to the delegate; pass `null, null` to the
  constructor.
- **Breaking:** vok-dataloader 0.9.1 (0.15: 0.4), an `api` dependency since `keyLoader()` returns
  its `DataLoader`. `DataLoader` itself is unchanged; gone are `PagedList`, `SqlWhereBuilder`,
  `LikeFilter`, `ILikeFilter` and the `withFilter()` extension. Migration: only code that called
  those has anything to change.
- **Breaking:** `PasswordHash` can no longer be instantiated and lost its `main()`. Migration: call
  its static methods, which are unchanged.
- **Breaking:** not binary compatible with 0.15: code compiled against 0.15 that calls one of the
  above fails at runtime. Migration: recompile against 0.16.
- Dependencies: h2-mvstore 1.4.198 (same file format as 0.15's 1.4.197); okhttp 3.12.2 in
  `umn-proxy-client` (0.15: 3.13.1), the version Aedict already pinned 0.15 to.
- `umn-proxy-server` needs Java 17; its Docker image runs on Java 25.
- `ProxyClient` no longer logs the password: 0.15 logged every request header at debug level,
  the `password` header included.
- A change no longer goes missing when the `Syncer` switches clients (`stop()`/`start()`): 0.15
  skipped a change sent to the old client but not yet confirmed, so it never reached the global
  log or the other nodes. The new client re-sends every unconfirmed change; a duplicate is
  harmless. Likewise, a change arriving from the old client as it closes is no longer applied
  out of order, nor does it move the sync position under the new client.
- `ProxyClient` notices a lost connection: once the connection fails or the server closes it,
  `isStarted` turns false and `addAsync()` throws `IOException`. 0.15 stayed "started" and sent
  every later change into the dead socket. A failed client never reconnects; take a new one.
- The `Syncer` reconnects by itself: when a client's connection fails, or it can't connect at
  all, the syncer closes it and takes a new one from `clientProvider`, waiting from 1 second up
  to 1 minute between attempts. 0.15 never recovered until the app stopped and started the
  syncer. Only a wrong username or password stops it.
- `Syncer.setStatusListener()` and `Syncer.status` tell the app what the syncer is doing:
  stopped, connecting, connected, offline until the next attempt, or failed on a wrong username
  or password, which 0.15 only logged.
- The proxy server no longer loses records when several devices of a user connect for the first
  time at once: H2 MVStore's `openMap()` isn't atomic while the map doesn't exist yet, so each
  device could get a log of its own, and the records written into all but one never reached the
  other devices. 0.15 has the bug too.
- `MvstoreUMN.openUtilityMap()` no longer splits a new utility map in two when threads open it
  at once, which lost what was written through one of them. 0.15 has the bug too.
- The proxy server announces a new record to every device of the user even when sending to one
  of them fails, say it just disconnected; 0.15 skipped the devices after it until the next
  record arrived.
- `InMemoryGlobalLog` no longer fails a client's `start()` with `ConcurrentModificationException`
  when another client adds a record meanwhile, nor delivers that record before older ones.
- **Breaking:** `ProxyClient.start()` throws a connection failure (`ConnectException`,
  `UnknownHostException`, a server's close) as the `IOException` itself, as
  `GlobalLogClient.start()` documents; 0.15 wrapped it in an `ExecutionException`. Migration:
  catch `IOException`.
