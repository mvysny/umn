# Upgrade okhttp past 3.x

`umn-proxy-client` is on okhttp 3.14.9, the last 3.x; the latest was 5.5.0 on 2026-10-18 (re-run
the `maven-versions` script before acting). Delayed, not blocked: okhttp is `api` in
`umn-proxy-client`, so a new major lands on Aedict's classpath and waits until Aedict is ready
for one.

## Before bumping

- `umn-proxy-server-adminclient` uses okhttp too (`implementation`); it follows along.
- okhttp 4+ is Kotlin and drags in kotlin-stdlib; check the version it wants against ours.
- A `Breaking:` CHANGELOG line, since okhttp's major is part of the API.

## Open questions

- `Q_okhttp_target`: straight to 5.x, or 4.12 as the last 4.x step first?
