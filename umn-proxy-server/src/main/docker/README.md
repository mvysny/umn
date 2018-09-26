# UMN Proxy Server Docker image

## Using images from dockerhub

TBD

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
  pkcs12: /opt/keystore.p12
```

Then, use `-v yourkeystore.p12:/opt/keystore.p12` to mount the keystore file into your
image; also don't forget to add `-p 10146:10146` to expose the SSL port from your image.

## Building custom images

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
docker run --rm -ti -p 10144:10144 -p 127.0.0.1:10145:10145 -v $HOME/db:/var/umn-proxy-server/db umn-proxy-server
```
