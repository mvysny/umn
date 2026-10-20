# Proxy-server Docker image on Java 25

`umn-proxy-server/Dockerfile` runs on `eclipse-temurin:21-jre`; 25 is the newer LTS. Left for v2
of the dependency upgrade: 21 is supported and nothing needs 25.

## When acting

- CI's build matrix (`.gitlab-ci.yml`) already tests on JDK 25, the JDK the image would run.
- The bytecode stays Java 17 (`-Xjdk-release=17`); only the image's runtime moves.
- Update the CHANGELOG line "its Docker image runs on Java 21".
