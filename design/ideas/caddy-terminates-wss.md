# Let Caddy terminate wss on 10146, not the server's Jetty

Today `secure:` makes Jetty serve wss from a PKCS12 keystore it reads once at startup. Every
Let's Encrypt renewal needs a certbot post-hook that converts the PEMs with `openssl pkcs12`, then a
server restart, which drops every node's connection.

## Proposal: document Caddy as the preferred wss setup

Caddy listens on 10146, gets and renews the certificate itself, and forwards plain ws to the
server on localhost:

```
umn.example.com:10146 {
    reverse_proxy 127.0.0.1:10144
}
```

The server's `config.yaml` drops `secure:` entirely. Nothing changes in UMN code or on the wire:
clients keep `wss://umn.example.com:10146`, and TLS sits below the wire format
`D_frozen_0_15_formats` freezes.

What the Caddy docs say (checked 2026-10-30, caddyserver.com/docs):

- **WebSockets need no config.** `reverse_proxy` sees the upgrade and turns the connection into a
  bidirectional tunnel; Caddy 1's `websocket` sub-directive is gone. It has no stream timeout by
  default, and `ProxyClient` pings every minute anyway.
- **Renewal runs in the background, with no restart and no dropped connection.**
- **A config reload drops the websockets**: `systemctl reload caddy` force-closes them unless you
  set `stream_close_delay`. That's harmless because the `Syncer` reconnects (`D_reconnect_in_syncer`).
- **ACME needs port 80 (HTTP challenge) or 443 (TLS-ALPN) reachable from the internet.** The CA
  never connects to 10146. If Caddy already serves the same hostname on 443, both sites share the
  certificate.
- **It sets `X-Forwarded-For/-Proto/-Host`.** The plain connector has no
  `ForwardedRequestCustomizer`, only `Secure.createJettyConnector` does, so the server sees
  127.0.0.1 for every client. Nothing reads the client address today.

Plain ws already listens on localhost only (`Config.host`), so Caddy on the same host reaches it
and nothing else does.

**Docker: Caddy runs on the host as well.** It proxies to the container's
`-p 127.0.0.1:10144:10144`, the same `Caddyfile` as above. The docs don't ship a Caddy container
or a compose file for it; a Docker user can write that themselves.

## Old Android: the app bundles the Let's Encrypt roots

Let's Encrypt's chain ends at ISRG Root X1, which Android trusts only from 7.1.1. The DST Root CA X3
cross-sign that covered older devices expired in 2024. Today's Jetty setup has the same problem.
The app fixes it by trusting the roots itself, and UMN's code doesn't change: `ProxyClient` already
takes the app's `OkHttpClient`. UMN's floor stays at API 22, so the docs cover both halves below.

- **API 24+: a network security config.** Aedict does this (`aedict-apk/src/main/res/xml/
  network_security_config.xml`, commit b0a5937). It keeps `<certificates src="system"/>` and adds
  `@raw/isrg_root_x1` and `@raw/isrg_root_x2` (X2 covers an ECDSA chain), PEMs taken from the
  Mozilla CA store. The manifest's `<application android:networkSecurityConfig=…>` points at it.
  okhttp uses the platform's default trust manager, so `ProxyClient` gets the roots with no code.
- **API 22–23: code.** These versions ignore the network security config. The app builds an
  `X509TrustManager` that checks against the system roots plus the bundled PEMs, then passes it to
  `OkHttpClient.Builder.sslSocketFactory(factory, trustManager)`. Nobody has tried this on an
  API 22 emulator yet.

## Minimum versions

- **Caddy: any 2.x.** WebSocket proxying has worked out of the box since 2.0.
- **Ubuntu: 24.04 noble**, the first LTS with `caddy` in the distro (universe, 2.6.2). 26.04
  resolute also ships 2.6.2, and 26.10 ships 2.11.4. 22.04 jammy has no package; the only route
  there is Caddy's own apt repo, a third-party source.

## Alternatives

- **Jetty reloads the keystore in place**: `secure: keystoreReloadSeconds` already does that (see
  the proxy server's README). It needs no new daemon, but the certbot plus `openssl pkcs12` deploy
  hook stays.
- **nginx or HAProxy** work the same way but have no built-in ACME: certbot plus a reload hook again.

`secure:` stays as it is: Caddy is documented next to it and doesn't replace it.

## Test plan

The proposal changes no UMN code, so nothing new gets committed: the wire is unchanged, and
`MainTest` already covers plain ws. Instead, each claim above gets checked once, by hand, and the
outcome is written next to its step here; on graduation the outcomes go into the READMEs.

**Setup, shared by steps 1–3.** Caddy 2.6.2 from resolute's universe, the version this file names
as the floor. Stop the package's systemd unit and run `caddy run` as the user instead. The site is
`localhost:10146` with `tls internal`, plus the global `skip_install_trust` so Caddy leaves the
system trust store alone. Clients trust Caddy's own root,
`~/.local/share/caddy/pki/authorities/local/root.crt`. The server is HEAD's distribution zip
with plain ws on 127.0.0.1:10144 and no `secure:`.

1. **JVM through Caddy.** Write a throwaway copy of `MainTest`'s `serves wss on the secure port`
   that trusts Caddy's root, and run two nodes syncing through `wss://localhost:10146`.
   - a. Records sync both ways. This checks "WebSockets need no config".
   - b. Leave the nodes idle for 15 minutes, then write. The write syncs, and the server logs no
     new connection. This checks that Caddy has no stream timeout.
   - c. Edit the Caddyfile and run `caddy reload` with both nodes connected. The connection drops,
     the `Syncer` reconnects, and a write made during the drop arrives.
   - d. Renewal: set a short leaf lifetime with `issuer internal { lifetime … }` (check that 2.6.2
     has it), then keep a node connected across a renewal Caddy logs. The node stays connected,
     and a new `openssl s_client` sees a new serial.
   - e. `ss -ltnp` shows 10144 on 127.0.0.1 only.
2. **Docker.** Build the image from `umn-proxy-server/Dockerfile` and run it under podman with
   `-p 127.0.0.1:10144:10144`, with the same Caddyfile. Repeat 1a and 1e.
3. **Android, local CA.** This tests the trust mechanism only. The ISRG PEMs need no test of
   their own: Aedict ships them in its network security config, and no errors have been reported
   from production. Use a
   throwaway app (not committed) whose `ProxyClient` syncs one record. `adb reverse tcp:10146
   tcp:10146` lets the emulator reach `localhost:10146` with the certificate's own hostname.
   - a. API 24 (the existing `API_24` AVD, 7.0): without a network security config the sync fails
     with "Trust anchor for certification path not found", which reproduces the trap. With a
     config that adds Caddy's root, it syncs.
   - b. API 22: the same failure without the code, and a sync with the `X509TrustManager` that
     merges the system roots with Caddy's. An HTTPS GET to a public site still succeeds, which
     proves the trust manager merges the roots rather than replacing them. Only `API_21` and
     `API_24` AVDs exist here: the `android-22` system image is a download from Google's SDK
     repo, so ask first.

## On graduation

- The proxy-client README gets the old-Android trust-anchor trap, with both halves: Aedict's
  network security config for API 24+, and the trust-manager code for API 22–23, checked on an
  API 22 emulator first. Without either, wss fails on Android below 7.1.1 with "Trust anchor for
  certification path not found".
