# Upgrade okhttp to 5.x

`umn-proxy-client` is on okhttp 4.12.0, the last 4.x; the latest was 5.5.0 on 2026-10-18 (re-run
the `maven-versions` script before acting). Delayed, not blocked: okhttp is `api` in
`umn-proxy-client`, so a new major lands on Aedict's classpath and waits until Aedict is ready
for one.

## Before bumping

- `umn-proxy-server-adminclient` uses okhttp too (`implementation`); it follows along.
- Check the Android API level and the kotlin-stdlib version 5.x wants against ours.
- A `Breaking:` CHANGELOG line, since okhttp's major is part of the API.
