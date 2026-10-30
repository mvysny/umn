# Hot-reload the `secure:` keystore with Jetty's `KeyStoreScanner`

`Secure.createJettyConnector` reads the PKCS12 keystore once, at startup. Every Let's Encrypt
renewal therefore needs a certbot post-hook (`openssl pkcs12 -export …`) plus a server restart, and
the restart drops every node's connection. Jetty can reload the keystore in place instead. That
keeps `secure:` as the zero-extra-daemon option beside `caddy-terminates-wss.md`.

## What Jetty offers (read from jetty-util 12.1.14 sources, the version we pin)

`org.eclipse.jetty.util.ssl.KeyStoreScanner(sslContextFactory)` is a `ContainerLifeCycle`:

- **What it watches.** It watches the keystore's **parent directory** at depth 1, every 1 s by
  default (`scanInterval`, in seconds). It matches events on the keystore's path *string*.
- **On change or add**, it calls `sslContextFactory.reload {}`, which runs unload plus load under
  the factory's lock. If loading fails, it logs `Keystore Reload Failed` at warn and keeps
  scanning.
- **On remove**, it calls `sslContextFactory.unload {}`, so new handshakes fail until the file
  comes back.
- **Existing connections are untouched.** Only new TLS handshakes see the new certificate, so
  nobody gets disconnected.

## Sketch

**Off by default, switched on in `config.yaml`.** Existing deployments keep today's behaviour,
including any keystore set up as a symlink or mounted as a single file in Docker, which reloading
can't see (see the gotchas below). One key in the `secure:` stanza both switches it on and sets
the scan interval. The name is explicit about what it reloads:

```yaml
secure:
  port: 10146
  host: 0.0.0.0
  pkcs12: /opt/umn-tls/keystore.p12
  keystoreReloadSeconds: 60     # absent or 0: read once at startup, as today
```

`Secure` gains `var keystoreReloadSeconds: Int = 0`. It goes last, so the positional
`Secure(666, "127.0.0.1", …)` in `ConfigTest` still compiles. In `Secure.createJettyConnector`,
which already gets the `Server`:

```kotlin
if (keystoreReloadSeconds > 0) {
    server.addBean(KeyStoreScanner(sslContextFactory).apply { scanInterval = keystoreReloadSeconds })
}
```

As a bean of the not-yet-started server, it starts and stops with it. `Main` should print whether
reloading is on next to the `wss://` line, so an operator can see it took effect.

**An older server refuses a config that has the new key.** snakeyaml fails on unknown properties,
so a `config.yaml` written for this release won't load on 0.16 or earlier. This affects config
files only, not `D_frozen_0_15_formats`, but the README should say which release added the key.

## Gotchas to document

- **Replace the keystore atomically.** `openssl pkcs12 -export -out /opt/keystore.p12` truncates
  and rewrites in place, so a scan landing mid-write loads a torn file. It logs a warning, then
  recovers on the next scan, but that's noisy. Have the post-hook write `/opt/keystore.p12.new`,
  then `mv` it over the old one.
- **The keystore must be a real file, not a symlink** into `/etc/letsencrypt/archive`. The scanner
  sees the link's directory, not the target's, so renewals would go unnoticed. The README's
  `openssl` step already writes a real file.
- **Docker: mount the directory, not the file.** `-v keystore.p12:/opt/keystore.p12` (as
  `src/main/docker/README.md` says today) pins the inode. After the host's `mv`, the container
  keeps seeing the old file forever. Mount `-v /opt/umn-tls:/opt/umn-tls` and point `pkcs12:` inside
  it.

## On graduation: these warnings must reach the READMEs

The owner calls these warnings vital. Getting any of them wrong silently stops renewals, so the
implementing commit must not delete this file before both READMEs carry them:

- `umn-proxy-server/README.md`, in the Let's Encrypt section:
  - `keystoreReloadSeconds:` in the `secure:` example, off by default, and the release that added
    it;
  - the keystore must be a real file, not a symlink;
  - the post-hook writes to `.p12.new` and then `mv`s it over the old file;
  - with `keystoreReloadSeconds` set, no restart is needed. Without it, the restart step stays as
    today.
- `umn-proxy-server/src/main/docker/README.md`: mount the keystore's directory, never the single
  file, with the reason (the bind mount pins the inode). Its `-v yourkeystore.p12:/opt/keystore.p12`
  example and the `pkcs12:` path in its `config.yaml` change to match, and gain
  `keystoreReloadSeconds:`.
- `CHANGELOG.md`: a line for the new key.

## Testing

`MainTest.serves wss on the secure port` already has `localhost.p12`. Add a second self-signed
keystore (different serial or subject), start the server on a temp copy of the first, `mv` the
second over it, wait for a scan, then open a fresh wss connection. With
`keystoreReloadSeconds: 1`, check that the peer certificate changed and that the websocket opened
before the swap still works. Without the key, check that the first certificate is still served.
`ConfigTest` also checks that `keystoreReloadSeconds` parses and defaults to 0.

## Open questions

- `Q_old_android_ca`: the Let's Encrypt trust problem on Android below 7.1.1, shared with the
  Caddy route. See `caddy-terminates-wss.md`.
