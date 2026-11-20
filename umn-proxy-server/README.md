# UMN Proxy Server

The UMN Proxy Server stores the global log that all your UMN nodes sync through. The nodes reach it
over an http(s) websocket using the very thin [umn-proxy-client](../umn-proxy-client), which works on
Android. Your nodes need nothing but an internet connection; the server needs nothing but a Java JRE:

```
[Your App] --uses--> [UMN] <---uses---> [ProxyClient] <---INTERNET---> [UMN Proxy Server] --stores--> [H2 MVStore file]
```

The server keeps a separate global log per user, in a single H2 MVStore database file (the `MVStoreGlobalLog`).

## How it plugs into UMN

UMN uses the [GlobalLogClient](../umn-core/src/main/kotlin/com/gitlab/mvysny/umn/sync/GlobalLogClient.kt)
interface to reach the global log shared with all other UMN nodes. All sync logic lives in the node, so a
`GlobalLogClient` is just a thin wrapper around a native client for some server. `ProxyClient` is the
implementation that talks to this server.

## Configuration and Deployment

The deployment is simple. First you need to compile the server:

1. Install Java JDK 17 or higher
2. `git clone https://github.com/mvysny/umn`
3. `cd umn && ./gradlew umn-proxy-server:build -x test`
4. Find the zip file at `umn-proxy-server/build/distributions/*.zip`

To deploy:

1. Install Java JRE 17 or higher
2. Unzip the zip file
3. Run `./proxy`

The server will load the configuration file from the following locations:

1. `config.yaml` at the current working directory
2. `/etc/umnproxyserver/config.yaml`

Example content of the configuration file:

```yaml
port: 10144
host: 127.0.0.1
adminPort: 10145
adminHost: 127.0.0.1
proxy:
  standalone:
    db: ./db
secure:
  port: 10146
  host: 0.0.0.0
  pkcs12: /opt/keystore.p12
```

Plain ws on 10144 listens on localhost only, the default since 0.17: it sends the passwords
unencrypted, so remote clients connect over wss (Caddy in front, or the `secure:` stanza; see
below), never plain ws.
`secure:` adds wss on 10146 beside plain ws, so local clients may keep `ws://localhost:10144`.

`proxy: standalone:` is the only log storage there is; its `db` is the directory holding the database
file (just a single file, `umn_proxy_log`), created automatically. To create users, please use the
Admin REST API as described below.

**Upgrade only forward: never start an older server version on a log a newer one has written.**
0.19 and later close the log with H2's clean-shutdown flag, which 0.18 and older keep while they
write; started again, the newer server trusts the stale flag and opens the log silently missing
records the older one appended. Fix a bad release with a newer one.

On every start the server first rewrites `umn_proxy_log` into a compact copy, dropping the space
that deleted records and users left behind, and swaps it in; since 0.18. Expect:

- **A slower start:** as long as copying the live data takes; a 3.8 GB log holding 755 MB of live
  data took 20 s on a fast disk. The ports open only once it's done.
- **Free disk the size of the live data** on the `db` volume, for that while: the copy is
  `umn_proxy_log.compacting` beside the log. With too little, the server logs a warning and starts
  on the log as it is.
- **A kill during it is safe:** the log is replaced only once the copy is complete and on disk; the
  next start deletes a half-written copy.

`compactDatabaseOnStartup: false` under `standalone:` turns it off.

### Secure WebSockets over SSL/HTTPS

Remote clients connect over wss, with the `wss://somehost:10146` URL in their UMN Proxy client.
There are two ways to serve it: put Caddy in front of the plain ws port, which gets and renews the
certificate by itself, or let the server serve wss on its own from a PKCS12 keystore, with the
`secure:` stanza. Clients can't tell the two apart.

Android below 7.1.1 doesn't trust Let's Encrypt's root, behind either setup, and fails with
"Trust anchor for certification path not found"; the app has to bundle the root, see
[old Android](../umn-proxy-client/README.md#old-android-and-lets-encrypt).

#### Caddy

[Caddy](https://caddyserver.com) listens on 10146, gets a Let's Encrypt certificate and renews it
in the background, with no restart and no dropped connection, and forwards plain ws to the server
on localhost. Any Caddy 2.x works; Ubuntu ships it from 24.04 on (`sudo apt install caddy`), while
22.04 has it only from Caddy's own apt repo. Put this into `/etc/caddy/Caddyfile` and run
`sudo systemctl reload caddy`:

```
{
	default_sni umn.example.com
}
umn.example.com:10146 {
	reverse_proxy 127.0.0.1:10144
}
```

The server's `config.yaml` has no `secure:` stanza, and plain ws keeps listening on `127.0.0.1`, so
only Caddy reaches it.

- **Keep `default_sni`.** A client that sends no SNI fails the handshake with `internal_error`,
  since Caddy has no certificate to pick. Every client connecting by IP address sends none, and so
  does Java for a dotless host such as `localhost`.
- **Let's Encrypt has to reach port 80 or 443** on the host, for its HTTP or TLS-ALPN challenge;
  it never connects to 10146. When Caddy serves the same hostname on 443 too, both sites share
  the certificate.
- **WebSockets need no configuration**, and Caddy adds no idle timeout of its own. The server
  closes a connection idle for 5 minutes, and `ProxyClient` pings every minute against it since
  0.17; give an older one an `OkHttpClient` with a `pingInterval`.
- **A config reload drops every websocket**, unless the Caddyfile sets `stream_close_delay`. The
  nodes' syncers reconnect by themselves.
- The server sees every client as `127.0.0.1`: it neither uses nor logs Caddy's `X-Forwarded-For`.

nginx or HAProxy work the same way, but they don't get certificates by themselves: they need
certbot and a reload hook, as `secure:` does.

#### The server's own wss

Store your certificate and private key in a passwordless PKCS12 key store, and provide the
`secure:` stanza in the `config.yaml` as shown above.

##### Let's Encrypt

You can use UMN Proxy server with Let's Encrypt easily. You just need to make sure to
convert PEM produced by Let's Encrypt to PKCS12 (taken from excellent [LetsEncrypt certs with embedded Jetty on](https://danielflower.github.io/2017/04/08/Lets-Encrypt-Certs-with-embedded-Jetty.html)):

```bash
cd /etc/letsencrypt/live/www.crickam.com
openssl pkcs12 -export -in fullchain.pem -inkey privkey.pem -out /opt/keystore.p12 -name crickam -CAfile chain.pem -caname root -passout "pass:"
chmod a+r /opt/keystore.p12
```

Then configure UMN to activate the secure socket on port 10146: add the following to your `config.yaml`:
```yaml
secure:
  port: 10146
  host: 0.0.0.0
  pkcs12: /opt/keystore.p12
  keystoreReloadSeconds: 60
```

`keystoreReloadSeconds: 60` makes the server check the keystore every 60 seconds and serve a changed one
to new connections, with no restart and no dropped connection. It is off by default (absent or `0`:
the keystore is read once at startup), and it needs UMN Proxy Server 0.17 or newer; an older server
refuses to start with the key in its `config.yaml`.

Certbot renews the certificate every couple of months; convert it after each renewal with a deploy
hook, e.g. `/etc/letsencrypt/renewal-hooks/deploy/umn-keystore.sh` (make it executable):

```bash
#!/bin/sh
set -e
cd "$RENEWED_LINEAGE"
openssl pkcs12 -export -in fullchain.pem -inkey privkey.pem -out /opt/keystore.p12.new -name crickam -CAfile chain.pem -caname root -passout "pass:"
chmod a+r /opt/keystore.p12.new
mv /opt/keystore.p12.new /opt/keystore.p12
```

Write the new keystore beside the old one and `mv` it over, so the server never reads a half-written
file. `pkcs12:` may also point at a symlink, or into a symlinked directory: retargeting the symlink at
a new file counts as a change too.

Without `keystoreReloadSeconds`, the hook also has to restart the UMN Proxy Server to load the new
key.

## Admin Interface

UMN Proxy Server offers the administration interface as a REST endpoint. By default the interface
listens on http 10145 on localhost only, no credentials are required.

To control the UMN Proxy Server programmatically, see the Java client: [umn-proxy-server-adminclient](../umn-proxy-server-adminclient).

### User management

The purpose of this API is to add, update and remove users that are allowed to sync via the UMN Proxy Client.
For every user a separate global log is created.

| Method+Endpoint | Explanation
| --------------- | -----------------------------
| GET /admin/users | Returns all users, as a JSON List of String usernames. Example: `curl -i localhost:10145/admin/users` should print `["user", "user25"]`
| PUT /admin/users | Creates a new user with given plaintext password, passed as a JSON body `{"username": "...", "password": "..."}`; the `Content-Type` doesn't matter. If the user already exists, its password is updated. Example: `curl -i -X PUT localhost:10145/admin/users -d '{"username":"user","password":"example"}'` should print 204 NO CONTENT on success. Servers before 0.17 only take the credentials as query params: `curl -i -X PUT "localhost:10145/admin/users?username=user&password=example"`. 0.17+ still accepts that form when both params are present, but prefer the body: URLs end up in logs.
| DELETE /admin/users/:username | Deletes the user with given username. Example: `curl -i -X DELETE localhost:10145/admin/users/user` should print 204 NO CONTENT on success.

### Statistics

| Method+Endpoint | Explanation
| --------------- | -----------------------------
| GET /admin/stats/logsizes | Returns all users and their log sizes, e.g. `curl -i localhost:10145/admin/stats/logsizes` should print `{"user":25, "user2": 1645}`

## Using in docker

Please see [UMN Proxy Server Docker Documentation](src/main/docker) for more info.

## Embedding, e.g. in tests

Since 0.18 the server is on Maven Central too, to run inside your own JVM: a test or a dev run
then needs no Docker.

```kotlin
dependencies {
    testImplementation("com.gitlab.mvysny.umn:umn-proxy-server:0.19")
}
```

```kotlin
Main(Config(port = 0, adminPort = 0, proxy = Proxy(Standalone("build/umn-db")))).use { server ->
    server.start()
    val wsUrl = "ws://127.0.0.1:${server.port}"               // for ProxyClient
    val adminUrl = "http://127.0.0.1:${server.adminPort}/admin/"  // for AdminClient
    // ...
}
```

- `port = 0` and `adminPort = 0` bind free ports, so parallel test JVMs never clash; `server.port`
  and `server.adminPort` read them back once started.
- A `Main` starts once; `close()` stops it and closes the database, and another run takes a new
  `Main`.
- The server brings no SLF4J binding: it logs through your test's own.
- **Your Jetty may be lifted.** The server runs on Jetty and Javalin, kept off your compile
  classpath, but one JVM holds one Jetty: Gradle picks the highest version anything asks for, so an
  app on an older Jetty 12.1.x runs the server's in its tests. Harmless within 12.1. Where it
  isn't, or where the tests should run the very artifact production runs, start the Docker image
  through Testcontainers instead: a `GenericContainer` on `mvysny/umn-proxy-server:0.19`, ports
  10144 and 10145 exposed, waiting on `GET /admin/users` on 10145, the URLs from `getMappedPort`.
  That costs Docker wherever the tests run, and seconds per start.
