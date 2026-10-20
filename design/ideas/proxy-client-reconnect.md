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

## Bug 2 — records in flight at a client switch are skipped, then acked away (read, not reproduced)

`Syncer.stop()`/`start()` replace the client and the `GlobalLogPusher` but keep the
`MutationLog` and its `AckFIFOBuffer`. The buffer rewinds its poll pointer to the oldest
unacked item only in `resetStatus()` — on construction and `clear()`, i.e. once per process.

So a record `poll()`ed into a client that never delivers it (Bug 1's dead socket, or a
connection closed by `stop()` before `OnAdded` arrived) is skipped by the next client. The next
`OnAdded` then calls `ackLowest()`, which acks by position, not by identity: it removes the
*skipped* record, while the one actually delivered stays buffered (re-sent after the next process
start — a harmless duplicate, records are idempotent). The skipped record never reaches the
global log: **silent loss of a user's change.**

`Q_loss_confirm`: confirm with a test before fixing — an in-process proxy server, a client that
dies after `poll()` but before `OnAdded`, `stop()`/`start()`, then check the global log has
every record.

## Fix directions

- **Rewind on (re)connect:** when a pusher starts on a fresh client, rewind the buffer's poll
  pointer to the oldest unacked item (an `AckFIFOBuffer.rewindPoll()`), so delivery is
  at-least-once; duplicates are fine.
- **A dead client must look dead:** `ProxyClient` sets `closed` (or clears `started`) on
  `onFailure` and on any close it didn't ask for, so `isStarted` turns false.
- `Q_reconnect_owner`: who reconnects?
  - inside `ProxyClient`, as the KDoc promises — but its `rewind` header is fixed at
    `setReceiverCallback`, and a reconnect needs the puller's *current* position;
  - or in `Syncer`/`GlobalLogPusher`: replace a dead client with `clientProvider()` and a fresh
    `GlobalLogPuller` (which computes the rewind itself), with a back-off. Then the KDoc's
    promise moves from the client to the syncer, and the KDoc changes.
- `retryIO` should treat a failed `start()` (whatever exception wraps the IO failure, except
  `InvalidUsernamePasswordException`) as retryable, with a back-off growing to ~1 min — a
  phone offline for hours shouldn't spin every second.

## Tests

- A `umn-sync-tester` battery: kill the transport mid-session (stop the in-process proxy server,
  restart it on the same port), keep writing, then assert both nodes converge and the global
  log holds every record exactly once or more.
- The same with the server down at `start()`.
- Re-run Aedict's `umn-sync-test` phase 2 (network off → install → network on, *without*
  backgrounding the app; sync must resume by itself) and then phases 3–6.

## CHANGELOG

Both bugs are user-visible: a line under 0.16 for each fix.
