# Upgrade okhttp past 3.12

`umn-proxy-client` is on okhttp 3.12.2; the latest was 5.5.0 on 2026-10-18 (re-run the
`maven-versions` script before acting). Delayed, not blocked: okhttp is `api` in
`umn-proxy-client`, so a new major lands on Aedict's classpath and waits until Aedict is ready
for one. The old Android reason is gone: minSdk 22, and okhttp 3.13+ needs API 21.

## Before bumping

- `umn-compat-0.15` reuses `libs.okhttp` as UMN 0.15's classpath (`umn015(libs.okhttp)`), and
  that one must stay 3.12.2, what Aedict-Online runs 0.15 on. Give it its own `umn015-okhttp`
  catalog entry first.
- `umn-proxy-server-adminclient` uses okhttp too (`implementation`); it follows along.
- okhttp 4+ is Kotlin and drags in kotlin-stdlib; check the version it wants against ours.
- A `Breaking:` CHANGELOG line, since okhttp's major is part of the API.

## Open questions

- `Q_okhttp_target`: straight to 5.x, or 4.12 as the last 4.x step first?
