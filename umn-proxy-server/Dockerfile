FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
COPY build/distributions/umn-proxy-server-*.zip /server.zip
RUN unzip /server.zip -d /opt/umn-proxy-server && rm -rf /server.zip
COPY src/main/docker/config.yaml /etc/umnproxyserver/config.yaml
VOLUME /var/umn-proxy-server/db
WORKDIR /opt/umn-proxy-server
CMD ["./proxy"]

