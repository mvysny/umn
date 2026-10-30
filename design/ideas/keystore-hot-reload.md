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

In `Secure.createJettyConnector`, which already gets the `Server`:

```kotlin
server.addBean(KeyStoreScanner(sslContextFactory))
```

As a bean of the not-yet-started server, it starts and stops with it. That's the whole code
change. The README's Let's Encrypt section then drops "restart the UMN Proxy Server".

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
  - the keystore must be a real file, not a symlink;
  - the post-hook writes to `.p12.new` and then `mv`s it over the old file;
  - no restart is needed any more.
- `umn-proxy-server/src/main/docker/README.md`: mount the keystore's directory, never the single
  file, with the reason (the bind mount pins the inode). Its `-v yourkeystore.p12:/opt/keystore.p12`
  example and the `pkcs12:` path in its `config.yaml` change to match.

## Testing

`MainTest.serves wss on the secure port` already has `localhost.p12`. Add a second self-signed
keystore (different serial or subject), start the server on a temp copy of the first, `mv` the
second over it, wait for a scan, then open a fresh wss connection. Check that the peer certificate
changed and that the websocket opened before the swap still works.

## Open questions

- `Q_scan_interval`: 1 s is Jetty's default. Renewals happen every ~60 days, so 60 s would do.
  Is it worth a `config.yaml` knob, or should it just be hardcoded?
- `Q_old_android_ca`: the Let's Encrypt trust problem on Android below 7.1.1, shared with the
  Caddy route. See `caddy-terminates-wss.md`.
