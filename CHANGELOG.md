# Changelog

What changes for a user of each release, newest first. A `Breaking:` line carries its migration
step; the disk and wire formats never break (`D_frozen_0_15_formats`). Releases before 0.16 have
no entries.

## 0.16 — unreleased

- 0.16 opens database files 0.15 wrote, and syncs with 0.15 nodes through the same proxy server,
  in both directions: the disk and wire formats are unchanged.
- The proxy server upgrades in place: it opens the 0.12 server's database (`umn_proxy_log`) as
  is, users, passwords and logs included.
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
