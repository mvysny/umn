# Contributing

Run `./gradlew build` to compile and test everything. Keep Docker running: without it the
`umn-compat-0.15` battery against the production proxy-server image is skipped. Testcontainers
also pulls its reaper image `testcontainers/ryuk`; `TESTCONTAINERS_RYUK_DISABLED=true` skips it.

Install Caddy too, `sudo apt install caddy` (Ubuntu 24.04+): without it `CaddyTest`, which syncs
through Caddy terminating wss in front of the proxy server, is skipped. Each test runs its own
`caddy run` on free ports with a throwaway local CA, so the package's `caddy.service` may keep
running. Its idle-connection test runs only when asked:
`CADDY_IDLE_MINUTES=15 ./gradlew umn-proxy-server:test --tests CaddyTest`.

# Releasing

To release the library to Maven Central:

1. Run `./gradlew clean build` with Docker running and Caddy installed, and check that no compat
   test or `CaddyTest` was skipped.
2. Edit `build.gradle.kts` and remove `-SNAPSHOT` from the `version =` stanza, e.g. "0.16"
3. Bump the version in the install snippets of `README.md`, `umn-proxy-client/README.md` and
   `umn-proxy-server-adminclient/README.md` to match the release, and the
   `mvysny/umn-proxy-server:` image tag in `README.md`,
   `umn-proxy-server/src/main/docker/README.md` and `docker-compose.yml` beside it; replace "unreleased" in
   `CHANGELOG.md`'s heading with the release date
4. Run `./gradlew clean build publish closeAndReleaseStagingRepositories`
5. (Optional) watch [Maven Central Publishing Deployments](https://central.sonatype.com/publishing/deployments) as the deployment is published.
6. Build and push the proxy server's Docker image, logged in to Docker Hub (`docker login`) as its
   owner: `cd umn-proxy-server && docker build -t mvysny/umn-proxy-server:0.16 . && docker push mvysny/umn-proxy-server:0.16`.
   Step 4's `clean` left exactly the release's zip in `build/distributions/`, which the `Dockerfile`
   copies; any other zip there gets in silently instead.
7. Commit with the commit message of simply being the version being released, e.g. "0.16"
8. git tag the commit with the same tag name as the commit message above, e.g. `0.16`
9. `git push`, `git push --tags`
10. Add the `-SNAPSHOT` back to the `version =` while increasing the version to something which will be released in the future,
   e.g. 0.17-SNAPSHOT, then commit with the commit message "0.17-SNAPSHOT" and push.

Publishing reads `sonatypeUsername` / `sonatypePassword` (a Central Portal user token) and the
`signing.*` GPG properties from `~/.gradle/gradle.properties`.
