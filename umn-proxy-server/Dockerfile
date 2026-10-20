FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
COPY build/distributions/umn-proxy-server-*.zip /server.zip
# the zip's top folder carries the version
RUN unzip /server.zip -d /opt && mv /opt/umn-proxy-server-* /opt/umn-proxy-server && rm -rf /server.zip
COPY src/main/docker/config.yaml /etc/umnproxyserver/config.yaml
VOLUME /var/umn-proxy-server/db
WORKDIR /opt/umn-proxy-server
CMD ["bin/umn-proxy-server"]
