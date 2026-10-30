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

## Minimum versions

- **Caddy: any 2.x.** WebSocket proxying has worked out of the box since 2.0.
- **Ubuntu: 24.04 noble**, the first LTS with `caddy` in the distro (universe, 2.6.2). 26.04
  resolute also ships 2.6.2, and 26.10 ships 2.11.4. 22.04 jammy has no package; the only route
  there is Caddy's own apt repo, a third-party source.

## Alternatives

- **Jetty reloads the keystore in place**: `keystore-hot-reload.md`. It needs no new daemon, but
  the certbot plus `openssl pkcs12` post-hook stays. It can ship as well as the Caddy docs, for
  people who keep `secure:`.
- **nginx or HAProxy** work the same way but have no built-in ACME: certbot plus a reload hook again.

## Open questions

- `Q_old_android_ca`: Let's Encrypt's chain ends at ISRG Root X1, which Android trusts only from
  7.1.1. The DST Root CA X3 cross-sign that covered older devices expired in 2024. So minSdk 22
  devices (5.1 to 7.1.0) may fail to validate a Let's Encrypt certificate, and that's equally true
  of today's Jetty setup. Should Caddy use its ZeroSSL issuer (a different root) instead? Verify
  on an API 22 emulator before recommending either.
- `Q_secure_stanza`: keep `secure:` as is, or deprecate it in the README in favour of Caddy?
  Removing it would break `config.yaml`, not the wire, but the Docker README documents it too.
- `Q_plain_bind`: once wss goes through Caddy, should the README suggest `host: 127.0.0.1` for
  10144? That hides plain ws from the internet, so passwords no longer cross it unencrypted. It
  only works if no client still connects over plain `ws://`.
- `Q_docker`: add a `caddy` service to `src/main/docker/docker-compose.yml` (official Docker Hub
  image), or describe only the host install?
