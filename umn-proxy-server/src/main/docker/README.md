# UMN Proxy Server Docker image

## Using images from dockerhub

UMN Proxy Server images are hosted on the Docker Hub at [UMN Proxy Server Docker Repository](https://hub.docker.com/r/mvysny/umn-proxy-server/).

To run the UMN Proxy Server, simply issue the following command in your terminal:

```
docker run --rm -ti -p 10144:10144 -p 127.0.0.1:10145:10145 -v $HOME/db:/var/umn-proxy-server/db mvysny/umn-proxy-server:0.11.2
```


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
  pkcs12: /opt/keystore.p12
```

Then, use `-v yourkeystore.p12:/opt/keystore.p12` to mount the keystore file into your
image; also don't forget to add `-p 10146:10146` to expose the SSL port from your image.

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
docker run --rm -ti -p 10144:10144 -p 127.0.0.1:10145:10145 -v $HOME/db:/var/umn-proxy-server/db umn-proxy-server
```
