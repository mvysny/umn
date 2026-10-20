# Review changes since 0.15 for incompatibilities

Before releasing 0.16, review everything since the `0.15` tag (2019-02-26) for changes that
break existing users: an app upgrading the library, a database file written by 0.15, a node
still on 0.15 syncing against the same global log. Scope: `git log 0.15..HEAD`.

The disk and wire formats (`D_frozen_0_15_formats`) are done: `umn-compat-0.15` holds HEAD to
them. What's left is the API, the inherited dependencies and the distribution; each finding
lands in `CHANGELOG.md`, a break as a `Breaking:` line with its migration.

## Disk and wire: not yet covered by `umn-compat-0.15`

- HEAD's proxy server opening the 0.12 server's `umn_proxy_log` file in place: verified only
  statically (same `users` / `{username}__log` maps, `PasswordHash`, MVStore 1.4.x).

## 3. Kotlin API and binary compatibility for library consumers

- `explicitApi()` (`45d7c30`..`69bb069`): did anything that was public in 0.15 become
  `internal` or `private`?
- Signature changes in `UMN.kt` (+93/−), `IndexingUMN.kt`, `Utils.kt`, `InMemoryUMN.kt`.
  Renames, new abstract members on `UMN`/`UtilityMap` (these break third-party
  implementations), changed defaults.
- `Q_api_diff_tool`: diff the public API by hand, or let a tool do it? Options are
  JetBrains' binary-compatibility-validator Gradle plugin, japicmp on the 0.15 and HEAD
  jars, or reading the diff. A tool means a new dependency; check its version via the
  `maven-versions` skill.

## 4. Dependencies that users inherit

- vok-dataloader 0.4 → 0.9.1 is an `api` dependency of `umn-core`, and its types are in the
  UMN API (`keyLoader()` returns a `DataLoader`). Any break in vok-dataloader is a break in
  UMN.
