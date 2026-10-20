# Contributing

Run `./gradlew build` to compile and test everything. Keep Docker running: without it the
`umn-compat-0.15` battery against the production proxy-server image is skipped.

# Releasing

To release the library to Maven Central:

1. Run `./gradlew clean build` with Docker running, and check that no compat test was skipped.
2. Edit `build.gradle.kts` and remove `-SNAPSHOT` from the `version =` stanza, e.g. "0.16"
3. Bump the version in the install snippets of `README.md`, `umn-proxy-client/README.md` and
   `umn-proxy-server-adminclient/README.md` to match the release
4. Run `./gradlew clean build publish closeAndReleaseStagingRepositories`
5. (Optional) watch [Maven Central Publishing Deployments](https://central.sonatype.com/publishing/deployments) as the deployment is published.
6. Commit with the commit message of simply being the version being released, e.g. "0.16"
7. git tag the commit with the same tag name as the commit message above, e.g. `0.16`
8. `git push`, `git push --tags`
9. Add the `-SNAPSHOT` back to the `version =` while increasing the version to something which will be released in the future,
   e.g. 0.17-SNAPSHOT, then commit with the commit message "0.17-SNAPSHOT" and push.

Publishing reads `sonatypeUsername` / `sonatypePassword` (a Central Portal user token) and the
`signing.*` GPG properties from `~/.gradle/gradle.properties`.
