# Ship the proxy-server Docker image with a release

Docker Hub's `mvysny/umn-proxy-server` stops at `0.12`, pushed 2018-09-27. Since then the
server opens the 0.12 database in place and its image runs on Java 25 (`umn-proxy-server/Dockerfile`),
but no image carries that. The release flow in `CONTRIBUTING.md` publishes the Maven Central
jars only, on purpose for 0.16.

## What shipping would take

- A release step: `./gradlew clean umn-proxy-server:build -x test`, then
  `docker build -t mvysny/umn-proxy-server:<version> umn-proxy-server` and `docker push`.
- Bump `:0.12` in `README.md` and `umn-proxy-server/src/main/docker/README.md` along with the
  install snippets.
- `umn-compat-0.15`'s `Servers.kt` pins the production image by digest, so a new push never
  changes what `OnProdServerTest` runs against.

## Open questions

- `Q_docker_where`: a manual step in `CONTRIBUTING.md`, or a GitLab CI job on the tag? CI has no
  Docker today; a job needs docker-in-docker or kaniko, and Docker Hub credentials as CI variables.
- `Q_docker_arch`: amd64 only, or `buildx` for arm64 too (a Raspberry Pi or ARM VPS host)?
- `Q_docker_latest`: tag `latest` as well, or only the version?
