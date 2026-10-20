# ProxyClient never reconnects; a reconnect may lose outgoing records

Found by Aedict's end-to-end sync test (its `umn-sync-test` skill, phase 2) on 2026-10-20;
fix before releasing 0.16, then re-run that test. Not a 0.16 regression: the 0.15 → HEAD
diff of `ProxyClient` and `SyncingUMN.kt` changes only visibility modifiers.

## Bug 1 — a failed ProxyClient stays "started" (reproduced)

`GlobalLogClient`'s KDoc promises "The client must reconnect automatically on non-fatal IO
exceptions such as connection failures." `ProxyClient` doesn't:

- `start()` sets `started` before connecting; `onFailure`/`onClosing` only fail
  `startFuture`, and nothing clears `started` or sets `closed`. So `isStarted` stays true.
- `start()` throws `ExecutionException` (wrapping okhttp's `UnknownHostException` etc.), but
  `retryIO` in `GlobalLogPusher` retries only `IOException`: the pusher logs "Pusher Thread
  failure", sleeps 5 s, sees `isStarted == true` and goes on `addAsync`-ing into the dead
  websocket, whose `send()` returns false unchecked.
- The same holds for a connection that drops mid-session (`onFailure` after `Started`): read
  in the code, not reproduced.

Repro: Aedict with sync on, network off, launch → `websocket failed: UnknownHostException`;
network on → nothing for minutes. Only Aedict's own `Synchronizer` (stops/starts the `Syncer`
on activity pause/resume, so `clientProvider` makes a fresh client) recovers it.

## Bug 2 — records in flight at a client switch are skipped, then acked away (reproduced)

`Syncer.stop()`/`start()` replace the client and the `GlobalLogPusher` but keep the
`MutationLog` and its `AckFIFOBuffer`. The buffer rewinds its poll pointer to the oldest
unacked item only in `resetStatus()` — on construction and `clear()`, i.e. once per process.

So a record `poll()`ed into a client that never delivers it (Bug 1's dead socket, or a
connection closed by `stop()` before `OnAdded` arrived) is skipped by the next client. The next
`OnAdded` then calls `ackLowest()`, which acks by position, not by identity: it removes the
*skipped* record, while the one actually delivered stays buffered (re-sent after the next process
start — a harmless duplicate, records are idempotent). The skipped record never reaches the
global log: **silent loss of a user's change.**

`Q_loss_confirm`: confirmed by a `umn-core` unit test instead — `InMemoryGlobalLog`, a client
wrapper that drops every `addAsync`, `stop()`/`start()`, then check the global log has every
record.

### Two late callbacks make it worse (read, not reproduced)

- **A late ack from a closed client.** okhttp's `ws.close()` only starts the close handshake;
  an `OnAdded` already in flight still reaches the old pusher's callback after `Syncer.stop()`
  and `ackLowest()`s a record the new client hasn't delivered. A rewind alone doesn't fix
  Bug 2: a closed pusher must ignore acks (check `closed` under the lock `close()` takes),
  which covers every `GlobalLogClient`, not just `ProxyClient`.
- **A late record into a closed puller.** The old `GlobalLogPuller`'s callback writes its own
  `++currentPtr` into `CURRENT_LOG_PTR` while the new puller counts from the same start, so a
  record is skipped or applied twice. Already in 0.15; the same "closed → ignore" guard fixes it.

## Fix order (decided)

1. ~~A failing `umn-core` test for Bug 2.~~ Done: `SyncingUMNTest.ClientSwitch`.
2. ~~Fix Bug 2~~ Done: `AckFIFOBuffer.rewindPoll()` when a pusher starts (at-least-once
   delivery, duplicates are fine), plus both late-callback guards, each with its test.
3. ~~A dead client must look dead~~ Done: `ProxyClient` turns `isStarted` false on `onFailure`
   and on a server's close, `addAsync()` then throws `IOException`, and `start()` rethrows an IO
   cause as the `IOException` itself. `GlobalLogClient`'s KDoc says a failed client never
   recovers. Tested by `GlobalLogClientTestBattery.ConnectionLossTests` through the
   `serverDown()`/`serverUp()` hooks, which only `UMNProxyServerTest` implements.
4. ~~The `Syncer` reconnects~~ Done: `Syncer.SyncLoop`, `Backoff`; tested in
   `SyncingUMNTest.Reconnect`, and end to end by `WriterReaderTestBattery`'s reconnect tests,
   which `UMNProxyServerTest.WriterReaderTests` runs over the real proxy server. Adding those
   found and fixed a server bug that lost records (`MVStoreGlobalLog.openMap()`).
   Design, as decided (`Q_reconnect_owner` answered): not the client, because "a new
   backend is a thin `GlobalLogClient`" (AGENTS.md promise), and the client's `rewind` is fixed
   at `setReceiverCallback` anyway. On a dead client the syncer closes client, pusher and
   puller, then takes a fresh `clientProvider()` client, `GlobalLogPuller` (computes its
   rewind) and `GlobalLogPusher` (rewinds the buffer). Back-off 1 s doubling to 60 s; every
   failure except `InvalidUsernamePasswordException` is retryable. The back-off is
   configurable for tests only (internal), so the batteries don't wait seconds.
   The `GlobalLogClient` KDoc changes from "must reconnect automatically" to "one client is
   one connection; once it fails, `isStarted` turns false and the syncer replaces it" — a
   failed client is never restarted. Wire and file formats unchanged.
5. CHANGELOG lines, then Aedict's `umn-sync-test`.

Reporting a fatal failure to the app is a separate idea: `syncer-failure-listener.md`.

## Tests

- `umn-core` units: the Bug 2 repro; `rewindPoll()`; a closed pusher ignores acks; a closed
  puller ignores records.
- `GlobalLogClientTestBattery`: `isStarted` turns false once the transport dies; `start()`
  against a server that is down throws `IOException`. Needs new battery hooks (drop all
  connections / restart the server): the proxy server's is `server.close()` plus a new
  `UMNProxyServer` on the same port and `lp`; `InMemoryGlobalLog` needs a small test-only
  "kill all clients".
- `SyncTestBattery`: kill the transport mid-session, keep writing, then assert all nodes
  converge and the global log holds every record at least once. The same with the server down
  at `start()`.
- `umn-compat-0.15` stays as is: the wire format doesn't change, and restarting the container
  on the same port is fiddly.
- Re-run Aedict's `umn-sync-test` phase 2 (network off → install → network on, *without*
  backgrounding the app; sync must resume by itself) and then phases 3–6.

## CHANGELOG

Both bugs are user-visible: a line under 0.16 for each fix.
