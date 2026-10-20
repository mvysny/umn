# Sync over a third-party log store (MongoDB, Kafka, Hazelcast…)

A `GlobalLogClient` over an existing server instead of the UMN Proxy Server's MVStore log.
Until 2026-10 the repo carried three: `umn-sync-mongodb`, `umn-sync-kafka`,
`umn-sync-hazelcast`. Nothing deployed used them, so they were deleted. The last commit that
has them is the parent of the deletion: `git log --diff-filter=D -- umn-sync-mongodb`. Rebuild
one only when someone needs it. Production syncs Aedict-Online and every Android app through
`umn-proxy-server` (`D_frozen_0_15_formats`).

## What a store must give

- **A total, append-only order** that every reader sees the same way: the **global-log order**
  invariant. Candidates are a MongoDB *capped* collection (natural order = insertion order; an
  uncapped collection guarantees no order), a single-partition Kafka topic, or a Hazelcast
  `IList`.
- **Durability.** Free Hazelcast keeps the `IList` in memory only. After a cluster restart the
  log is empty while every node's `__CURRENT_LOG_PTR` still points past its end, so nodes
  silently skip records.
- **Reads from offset _n_** (`rewind`) without scanning: Mongo's `find().skip(n)` is O(n) per poll.

## Where it plugs in

- None of these clients runs on Android. That is why the proxy exists: phones keep
  `ProxyClient`, and the store sits behind the server.
- So the natural host is `umn-proxy-server`: a config branch next to `proxy: standalone:` in
  `Config.Proxy` (which today throws for anything else), plus a `GlobalLogClientProvider`.
  User management then belongs to the store, and `AdminServer` (MVStore-only today) needs a
  story.
- Test with the `umn-sync-tester` batteries: `GlobalLogClientTestBattery`, `SyncTestBattery`,
  `WriterReaderTestBattery`.

## Traps the old code fell into

- Mongo: `start()` checked for and created the capped collection under a hard-coded
  `"mycollection"` but used `collectionName`, so any other name got an uncapped collection.
  The tests used random names and never caught it.
- Mongo change streams need a replica set, so the code polled. With a replica set available,
  change streams beat polling.
- Kafka: re-sending a record from the send callback after a `TimeoutException` lets later
  records land first, which reorders the log. Use `enable.idempotence` and let the producer
  retry. There was also a 2 s sleep for KAFKA-7085 (kafka-clients 1.1).
- A Testcontainers test that skips itself when Docker is missing, together with
  `failOnNoDiscoveredTests = false`, made the MongoDB backend look tested when it never ran.

## Open questions

- `Q_which_store`: is there a real need? Only build one when a deployment asks for it.
