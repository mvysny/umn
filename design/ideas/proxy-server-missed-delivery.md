# The proxy server sometimes doesn't deliver a record to a connected node

Found on 2026-10-20 while adding `WriterReaderTestBattery` over the real proxy server (step 4
of `proxy-client-reconnect.md`). Already at `c676454`, before the `Syncer` reconnected: not
caused by the reconnect work. Fix before releasing 0.16, then re-run Aedict's `umn-sync-test`.

## Symptom (reproduced)

Three `SyncingUMN`s over `ProxyClient`s to one in-process `UMNProxyServer`, all connected; one
writes one record. In about 1 run in 5 the record **is** in the server's global log, the writer
has it, but one connected reader still lacks it after 3 s. Nothing is logged at WARN or ERROR.
`WriterReaderTestBattery(2)` over the proxy fails about 1 run in 4 (`create() propagates
properly`, `create()+update() propagates properly`, `propagation creates indices properly`).

Not known yet whether production is affected: the `umn-compat-0.15` batteries pass, but they
write from one node at a time.

## Reproducer

The battery, nested in `UMNProxyServerTest` (it also enables the reconnect tests, which need
this fixed first):

```kotlin
@Nested inner class WriterReaderTests : WriterReaderTestBattery(2) {
    override fun newStorage(): UMN = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer)
    override fun newClient(): GlobalLogClient = createSyncClient(httpClient)
    override fun serverDown() { server.close() }
    override fun serverUp() { startServer() }
}
```

A faster probe, also in `UMNProxyServerTest`, printing where the record stopped:

```kotlin
@RepeatedTest(15) fun probe() {
    val nodes = List(3) { InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer).syncing({ createSyncClient(httpClient) }, JavaDocumentSerializer, true) }
    try {
        val id = nodes[0].create("hello")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 3000 && !nodes.all { it.exists(id) }) Thread.sleep(10)
        System.err.println("PROBE logSizes=${lp.mvstorelog.getLogSizes()} exists=${nodes.map { it.exists(id) }}")
    } finally { nodes.forEach { it.close() } }
}
```

## Suspects

- `MVStoreGlobalLogClient.start()` registers with the owner (`startClient`) before it assigns
  `lateinit var log`: an append from another node in between makes `fireNewItemNotification`
  throw `UninitializedPropertyAccessException` on this client, which aborts notifying the
  clients after it in the queue. **Swapping the two lines alone did not fix the probe**, so it
  is not the (only) cause; but `fireNewItemNotification` should notify every client even when
  one fails.
- The server's `Session.send` blocks until written; the add callback (`OnAdded`, on the
  session's own thread) and the receiver callback (`OnLogRecord`, on another node's thread) can
  send on one session concurrently. No `WritePendingException` was logged, but an exception
  thrown from a Javalin handler may not be logged at all — check that first.
- The client side: a record arriving before `Started` (backlog) vs. after.

Next step: DEBUG logging for `ProxyClient` and `UMNProxyWebsocketListener` in the probe, to see
whether the server sent the `OnLogRecord` to the reader that missed it.
