# Drop the unused sync backends: Kafka, MongoDB, Hazelcast

`umn-sync-kafka`, `umn-sync-mongodb` and `umn-sync-hazelcast` look like supported backends: the
README, AGENTS.md and the proxy-server README all list them. But nothing deployed uses them,
two of the three are untested, and all three are on 2018-era clients that need a three-major
upgrade (`upgrade-deps-java17-kotlin.md`). Proposal: delete the three modules, keep what they
taught as a single idea file, and fix the docs that promise them. This answers
`Q_backend_modules` in `upgrade-deps-java17-kotlin.md`.

## What is actually deployed — investigated 2026-10-20

- **Aedict (Android)** depends only on `umn-proxy-client:0.15` and syncs over
  `wss://aedict-online.eu:10146` (`aedict-common/.../umn/UMNUtils.kt`, `aedict-apk/.../KConfig.kt`).
  No Aedict module references a `umn-sync-*` artifact.
- **`umn-proxy-server` has no "Proxy Mode".** `Config.Proxy` has a single `standalone` field and
  `createLogClientProvider()` throws for anything else; the server depends on no `umn-sync-*`
  module. So whatever answers on 10146 stores its log in `MVStoreGlobalLog`, unless the server
  runs a fork. Its README describes Proxy Mode with a MongoDB diagram, then says "Currently only
  the standalone mode is supported".
- **The server confirms it (checked 2026-10-20).** Container `app_umn-proxy-server_1` runs
  image `mvysny/umn-proxy-server:0.12`, and the jars inside are `umn-core`, `umn-proxy-client`
  and `umn-proxy-server` `0.13-SNAPSHOT`, plus `h2-mvstore-1.4.197`. Published ports: 10146
  (wss) to the world and 10145 (admin) on 127.0.0.1 only. Plain ws 10144 isn't published.
  Nothing listens on a MongoDB, Kafka/ZooKeeper or Hazelcast port. The only other containers
  are `app_tomcat_1` (80/443) and `owm-city-finder-server`. Every tag from 0.12 to 0.15 has the
  standalone-only `Config.Proxy`, so the log is `MVStoreGlobalLog`.

### The deployed server is older than 0.15

`0.13-SNAPSHOT` means a build between `f90b105` (2018-09-27) and `f4ca711` (2018-10-02).
The Aedict-Online web app in `app_tomcat_1` (Vaadin) does run `umn-core`, `umn-proxy-client`
and `umn-proxy-server-adminclient` 0.15, so it is a sync node and manages users through the
admin REST API. `D_frozen_0_15_formats` now names both versions and freezes the admin API
too. Static diff `f90b105..0.15`:
- **Storage of `umn_proxy_log`: identical.** It has the same `users` and `{username}__log`
  maps, the same `PasswordHash`, and the same MVStore 1.4.197. An in-place server upgrade
  opens the existing file. (`MVStoreGlobalLog`'s KDoc still lists a `properties` map that
  `ca188f1` removed. That's stale.)
- **Wire: one-way extension.** 0.15 added "`OnLogRecord` with size 0 = hole (`null` record)".
  The old server never sends it, so 0.15 clients are fine. But a newer server that *punches
  holes* would break pre-0.15 clients, which matters if old Aedict installs are still around.
  The handshake order also moved: `Started` is now sent after `start()` succeeds, but the bytes
  and close codes are unchanged.
- Admin REST: not yet diffed between `f90b105` and 0.15. `f18cac3` ("AdminServer: provide
  message on failure") and the Javalin upgrades touched it. Covered by
  `review-incompatibilities-since-0.15.md` §4.

## State of each backend

| Module | Code | Tests | Published | Verdict |
|---|---|---|---|---|
| `umn-sync-hazelcast` | 110 lines, Hazelcast client 3.10.2 | run and pass (50 tests, embedded 3.10 member) | yes | works, but in-memory only |
| `umn-sync-mongodb` | 173 lines, driver 3.8.0, polling | silently skipped: never ran here or in CI | yes | broken |
| `umn-sync-kafka` | 200 lines, kafka-clients 1.1.0 | disabled in source (`&& false`) | no | untested, has a reorder bug |

`umn-sync-tester` stays: `umn-proxy-server`, `umn-proxy-client` and the in-memory/MVStore
global log tests run its batteries.

### Hazelcast
- Free Hazelcast keeps the `IList` in memory. When the cluster restarts the log is empty, while
  every node's `__CURRENT_LOG_PTR` still points past its end. The node then silently receives
  nothing until the new log grows past its pointer, and it skips those records too.
- `com.hazelcast.core.ItemListener` and friends are 3.x API. 4.x moved them and merged the
  client into `com.hazelcast:hazelcast`.

### MongoDB
- **Bug:** `start()` looks up and creates the capped collection under the hard-coded name
  `"mycollection"`, but reads and writes `collectionName`. Any other name gets an auto-created
  *uncapped* collection, and `find().skip(n)` on that collection has no guaranteed natural
  order. That breaks the **global-log order** invariant. The tests use random collection
  names, so they never exercise the capped path.
- The test skips itself on this machine: Testcontainers 1.19.8 talks Docker API 1.32 and
  Docker 29 rejects it ("client version 1.32 is too old. Minimum supported API version is
  1.44"). `failOnNoDiscoveredTests = false` hides this, and CI runs `-x test` anyway.
- Its README shows a constructor (`…, JavaDocumentSerializer, YourTypeResolver, 10000`) that
  doesn't exist. Each poll is `skip(n)` over the whole collection, O(log size).
- `addAsync` blocks on `insertOne`.

### Kafka
- **Bug:** a send `TimeoutException` re-sends the record from the callback, after the records
  sent later may already have landed. That reorders the global log.
- `consumer.poll(long)` and `close(long, TimeUnit)` were removed in kafka-clients 4.x. There is
  a `Thread.sleep(2000)` workaround for KAFKA-7085, `enable.idempotence` is commented out for
  "newer Kafka", and the README is all TBD.

## Plan

1. Delete `umn-sync-kafka`, `umn-sync-mongodb`, `umn-sync-hazelcast`, and their lines in
   `settings.gradle.kts`.
2. `libs.versions.toml`: drop `kafka-client`, `hazelcast`, `mongodb-driver`,
   `testcontainers-*` and the `testcontainers` version. Nothing else uses them; check with grep.
3. Docs:
   - `AGENTS.md`: the three module-map lines; "Kafka and MongoDB via Testcontainers" in
     Conventions; "the Kafka and MongoDB tests need Docker" in Commands.
   - `README.md`: the three bullets under the supported means of sync (~l.292), plus the
     Kafka/Hazelcast mentions at ~l.110 and ~l.343. Those two are about the design ("any server"),
     so they can stay as examples.
   - `umn-proxy-server/README.md`: drop "The Proxy Mode" and the MongoDB diagrams, and make
     standalone the only mode. Drop the "not running in standalone mode" note under User
     management.
   - `upgrade-deps-java17-kotlin.md`: the three dependency rows, the `failOnNoDiscoveredTests`
     note and `Q_backend_modules`. `review-incompatibilities-since-0.15.md` §5: note that the
     two published artifacts end at their last release.
4. Write `design/ideas/sync-over-third-party-log-stores.md`, holding what the code knew so
   it can be rebuilt on demand:
   - needs a store with a total append order: Mongo capped collection, single-partition Kafka
     topic;
   - Mongo change streams need a replica set, so the code polled instead;
   - none of the three clients runs on Android, which is why the proxy exists;
   - the proxy server is the natural host: a new `Proxy` config branch next to `standalone`;
   - the two bugs above, as traps to avoid.
5. Promises: **Sync over any server** stays true as a design property (`D_three_op_log`).
   `umn-proxy-client` is the one shipped proof, and the test batteries are the recipe.

## Open questions

- `Q_published_artifacts`: `umn-sync-mongodb` and `umn-sync-hazelcast` were published up to
  0.15 (Bintray, now dead). Do we say anything in the 0.16 release notes, or let them lapse
  silently?
- `Q_keep_hazelcast`: it is the only one of the three that works and is tested. Is it worth
  keeping as a dev/test backend, or does its in-memory log make it a trap? Leaning: drop.
- `Q_one_idea_or_three`: in step 4, write one idea file for all third-party stores, or one
  per store? Leaning: one; they share the design and none is wanted today.

## Checklist for the Aedict-Online server

Steps 1–3 were done on 2026-10-20 (results above): it is `umn-proxy-server` 0.13-SNAPSHOT,
and there is no other log store. The `docker-compose.yaml` service mounts
`./umn-proxy-server/db` (the log), `./umn-proxy-server/config.yaml` and `/root/keystore.p12`
from the host. Steps 4–6 are left; they're nice to have, not needed to decide. The goal was to confirm what answers on `aedict-online.eu:10146` and that it is
`umn-proxy-server` in standalone mode. On the server:

1. **What listens.** `sudo ss -ltnp`. Expect 10144 (ws), 10145 (admin, should be bound to
   127.0.0.1 only) and 10146 (wss). Any of 27017 (MongoDB), 9092/2181 (Kafka/ZooKeeper) or
   5701 (Hazelcast) would point at another backend.
2. **What runs it.** `docker ps -a --format '{{.Names}}\t{{.Image}}\t{{.Ports}}'`: expect
   `mvysny/umn-proxy-server:<tag>` or a locally built `umn-proxy-server`. If it isn't Docker:
   `ps aux | grep -iE 'umn|proxy|mongo|kafka|hazelcast'` and
   `systemctl list-units --all | grep -iE 'umn|proxy'`. Also look for a compose file:
   `docker inspect <c> --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}'`.
3. **Which version.** `docker exec <c> ls /opt/umn-proxy-server/lib/` and note the
   `umn-core-*.jar` / `umn-proxy-client-*.jar` versions. `D_frozen_0_15_formats` assumes 0.15.
   Also check the Java version (`docker exec <c> java -version`).
4. **Which backend.** `docker exec <c> cat /etc/umnproxyserver/config.yaml` (or `config.yaml`
   in its working dir). Expect `proxy: standalone: db: …` plus a `secure:` block pointing at a
   PKCS12 keystore. The startup log confirms it: `docker logs <c> 2>&1 | grep -E 'Using log
   provider|Sync Server is running'` should show `MVStoreGlobalLogProvider(dbdir=…)`.
5. **Live data.** On the host side of the `/var/umn-proxy-server/db` volume, expect one MVStore
   file `umn_proxy_log`. Its size and mtime show recent syncs. `curl -s
   localhost:10145/admin/stats/logsizes` gives per-user log sizes, and `docker logs --since 24h
   <c> | grep -c 'Got connection'` shows recent clients.
6. **TLS renewal** (the `secure:` keystore): how the Let's Encrypt → PKCS12 conversion and the
   restart are wired, e.g. a certbot deploy hook. It's not about the protocol, but it's the
   part most likely to break silently.

What would change the plan: a MongoDB, Kafka or Hazelcast process, or a `proxy:` key other than
`standalone` (which this code can't parse, so it would mean a fork running).
