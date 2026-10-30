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
  default. Jetty's 5-minute idle timeout still closes a quiet socket; `ProxyClient` pings every
  minute against it.
- **A client that sends no SNI fails the handshake** with `internal_error`, because Caddy has no
  certificate to pick. Every client omits SNI for an IP address, and the JDK also omits it for a
  dotless host such as `localhost`; the global `default_sni <host>` fixes both.
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

The proposal changes no UMN code and the wire is unchanged. Step 1 became `CaddyTest`, committed
in `umn-proxy-server`; it runs wherever `caddy` is on the PATH (`CONTRIBUTING.md`). Steps 2–3 are
checked once, by hand. Each step's outcome is written next to it here, and on graduation the
outcomes go into the READMEs.

**Setup for steps 2–3.** Caddy 2.6.2 from resolute's universe, the version this file names as the
floor, run as the user with `caddy run`. The site is `localhost:10146` with `tls internal`. The
globals are `CaddyTest`'s: `skip_install_trust`, `auto_https disable_redirects` (a user can't bind
the redirect server's :80), `default_sni localhost`, and an `admin` port beside the package's
running unit on 2019. Clients trust Caddy's own root, `pki/authorities/local/root.crt` under its
data dir. The server is HEAD's distribution zip with plain ws on 127.0.0.1:10144 and no `secure:`.

1. **JVM through Caddy.** Done 2026-10-30 on Caddy 2.6.2, first by hand against the zip, then as
   `CaddyTest` (about 30 s). Every claim held.
   - a. Records sync both ways, with no WebSocket config. The server sees
     `X-Forwarded-For/-Host/-Proto`.
   - b. A pair pinging every minute stayed connected for 15 idle minutes, then synced. A pair
     without pings was closed by Jetty (`1001 Connection Idle Timeout`) every 5 minutes, never
     sooner, so Caddy adds no timeout. `CaddyTest` runs this only with `CADDY_IDLE_MINUTES` set.
   - c. `caddy reload` of an edited Caddyfile drops both nodes. The `Syncer` reconnects, and a
     write made while offline arrives. An unchanged Caddyfile reloads nothing.
   - d. 2.6.2 has `issuer internal { lifetime }`, and the default leaf is 12 hours. With a 30 s leaf
     and `renew_interval 5s`, the served serial changed after about 25 s, and both nodes stayed
     `Connected`, with no reconnect.
   - e. `ss -ltnp`: 10144 and 10145 on 127.0.0.1 only, Caddy on 10146.
2. **Docker.** Build the image from `umn-proxy-server/Dockerfile` and run it under podman with
   `-p 127.0.0.1:10144:10144`, with the same Caddyfile. Repeat 1a and 1e. Done 2026-10-30 under
   rootless podman on Caddy 2.6.2, with the image built from the 0.17-SNAPSHOT zip and the docker
   README's `-p 127.0.0.1:10144:10144 -p 127.0.0.1:10145:10145`. Both claims held.
   - a. Two JVM nodes synced both ways through `wss://localhost:10146`. Caddy's upgrade to the
     container returned 101 with `X-Forwarded-For: 127.0.0.1`, `-Proto: https` and
     `-Host: localhost:10146`.
   - e. `ss -ltnp`: podman's `pasta` holds 10144 and 10145 on 127.0.0.1 only, and Caddy holds 10146.
     From the host's LAN address, 10144 and 10145 refuse connections and 10146 answers.
   - With an older zip still in `build/distributions`, podman's `COPY …-*.zip /server.zip` copies
     both, and the last in sort order wins without an error. Docker untried. The docker README's
     `./gradlew clean …` avoids that.
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
     proves the trust manager merges the roots rather than replacing them.

   Done 2026-10-30 on Caddy 2.6.2 and HEAD's server zip, with an app built against HEAD's
   `umn-core` and `umn-proxy-client` (AGP 8.13.2, minSdk 22). Every claim held. The new `API_22`
   AVD runs `system-images;android-22;google_apis;x86_64`.
   - a. API 24, with no config: `SyncerStatus.Offline` with the trust-anchor
     `SSLHandshakeException`, retrying every 1, 2, 4 and 8 s, and nothing reached the server. With
     `<certificates src="system"/>` plus `@raw/caddy_root`: `Connected`, and the record arrived.
   - b. API 22: the plain build fails the same way, and so does the build with the network security
     config, which proves API 22 ignores it. The merged trust manager syncs. It loads
     `TrustManagerFactory.init(null as KeyStore?)`'s `acceptedIssuers` (162 roots) and the
     bundled PEM into an empty `KeyStore`, then passes the result to
     `OkHttpClient.Builder().sslSocketFactory(SSLContext.socketFactory, trustManager)`. A GET to
     `https://www.google.com/` returned 200 with it.
   - The public site has to chain to an old root. `https://www.wikipedia.org/` serves a Let's
     Encrypt chain, so it fails on API 24 even with no Caddy in the path.

## On graduation

- The proxy-server README's Caddy section warns that a client connecting by IP address sends no
  SNI and fails with `internal_error` unless the Caddyfile sets `default_sni`.
- The proxy-client README gets the old-Android trust-anchor trap, with both halves: Aedict's
  network security config for API 24+, and step 3b's trust-manager code for API 22–23, which
  ignore the config. Without either, wss fails on Android below 7.1.1 with "Trust anchor for
  certification path not found".
