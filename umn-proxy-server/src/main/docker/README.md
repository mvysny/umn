# UMN Proxy Server Docker image

## Using images from dockerhub

UMN Proxy Server images are hosted on the Docker Hub at [UMN Proxy Server Docker Repository](https://hub.docker.com/r/mvysny/umn-proxy-server/).

To run the UMN Proxy Server, simply issue the following command in your terminal:

```
docker run --rm -ti -p 127.0.0.1:10144:10144 -p 127.0.0.1:10145:10145 -v $HOME/db:/var/umn-proxy-server/db mvysny/umn-proxy-server:0.12
```

Inside the container the server listens on all interfaces, since the host can't reach the
container's own localhost; the `127.0.0.1:` prefixes keep both ports off the network. Plain ws on
10144 sends the passwords unencrypted, so remote clients connect over wss (see Security below).


You can test that the server is running:

```
curl -i localhost:10145/admin/users
HTTP/1.1 200 OK
Date: Wed, 26 Sep 2018 09:35:21 GMT
Content-Type: text/html;charset=utf-8
Transfer-Encoding: chunked
Server: Jetty(9.4.12.v20180830)

[]
```

You can now press CTRL+C to terminate the server.

### Docker Compose

See the example [docker-compose.yml](docker-compose.yml) file which runs the server. Download the file,
then simply run the following from your terminal:

```bash
docker-compose up
```

### Security

By default the SSL WebSocket port is not enabled. To enable it, you need to modify the `config.yaml`
as follows:

```yaml
port: 10144
host: 0.0.0.0
adminPort: 10145
adminHost: 0.0.0.0
proxy:
  standalone:
    db: /var/umn-proxy-server/db
secure:
  port: 10146
  host: 0.0.0.0
  pkcs12: /opt/umn-tls/keystore.p12
  keystoreReloadSeconds: 60
```

Then, use `-v /opt/umn-tls:/opt/umn-tls` to mount the directory holding the keystore into your
image; also don't forget to add `-p 10146:10146` to expose the SSL port from your image.
Plain ws on 10144 keeps serving the host's local clients beside it.
`keystoreReloadSeconds` makes the server pick up a renewed keystore without a restart; see
[Let's Encrypt](../../../README.md#lets-encrypt) for the renewal hook.

**Mount the directory, never the keystore file alone.** A single-file bind mount
(`-v keystore.p12:/opt/keystore.p12`) stays on the file that was there when the container started:
once the renewal hook moves a new keystore over it on the host, the container keeps seeing the old
one forever, and keeps serving the old certificate until it expires.

## Building the image

To build the image, first build the server:

```bash
./gradlew clean umn-proxy-server:build -x test
```

Then you can build the docker image:

```bash
cd umn-proxy-server
docker build -t umn-proxy-server .
```

And run it:

```bash
docker run --rm -ti -p 127.0.0.1:10144:10144 -p 127.0.0.1:10145:10145 -v $HOME/db:/var/umn-proxy-server/db umn-proxy-server
```
