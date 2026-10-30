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

## On graduation

- The proxy-client README gets the old-Android trust-anchor trap, with both halves: Aedict's
  network security config for API 24+, and the trust-manager code for API 22–23, checked on an
  API 22 emulator first. Without either, wss fails on Android below 7.1.1 with "Trust anchor for
  certification path not found".
