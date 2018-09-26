# UMN Proxy Server Docker image

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
docker run --rm -ti -p 10144:10144 -p 127.0.0.1:10145:10145 umn-proxy-server
```

