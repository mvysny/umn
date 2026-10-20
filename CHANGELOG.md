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
- `umn-proxy-server` needs Java 17; its Docker image runs on Java 21.
- `ProxyClient` no longer logs the password: 0.15 logged every request header at debug level,
  the `password` header included.
