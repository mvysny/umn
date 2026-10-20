# Tell the app what the Syncer is doing

Split off `proxy-client-reconnect.md`. Once the `Syncer` reconnects by itself, the only
failure it doesn't retry is `InvalidUsernamePasswordException`: it stops syncing for good and
says so only in the log. The app (Aedict) can't show "wrong password, fix it in settings", and
the user sees sync silently stalled.

Out of scope for 0.16; reconnecting comes first.

## Decided

- `Q_status_scope` answered: the whole status, not only fatal failures, so Aedict can show
  "offline" too.
- `Q_listener_shape` answered: a plain listener interface. `umn-core` has no coroutines
  dependency and stays so on Android.
- `Q_listener_removal` answered: one listener only; `setStatusListener(null)` removes it.
- `Q_status_threads` answered: every status is delivered on the syncer's event-loop thread, so
  the listener sees them in order.

## Sketch

- A `SyncerStatus` sealed class, mapped onto `Syncer.SyncLoop.run()`:
  - `Stopped`: never started, or `stop()`/`close()` called.
  - `Connecting`: `clientProvider()` and `client.start()` running.
  - `Connected`: `client.start()` returned, the pusher runs. Not "caught up": the puller
    may still be replaying.
  - `Offline(cause, retryInMillis)`: the connection failed, waiting out the back-off.
  - `Failed(cause: InvalidUsernamePasswordException)`: syncing stopped; the app `stop()`s and
    `start()`s the syncer with new credentials.
- `fun interface SyncerStatusListener { fun onStatusChanged(status: SyncerStatus) }`, set by
  `Syncer.setStatusListener(listener?)`, plus a `Syncer.status` property. Setting a listener
  also posts the current status to it, so a UI that attaches late renders it without racing
  a change.
- The "UMN Sync" thread posts its statuses to the event loop with `event {}`; the event loop
  updates `status` and calls the listener, never on the UI thread, so the app hops to its UI
  thread. A throwing listener is logged and doesn't stop the syncer; a slow one delays
  `start()`/`stop()`, so the KDoc says to return quickly.
- A status posted by a `SyncLoop` that is no longer `loop` is dropped. Otherwise `stop()`
  publishes `Stopped`, while the loop it is closing has queued an `Offline` behind it, and the
  stale `Offline` wins.
- After `close()` the event loop is shut down: `Stopped` still goes out, as `stop()` runs with
  `forceRun`, and later posts are dropped (`RejectedExecutionException` caught).
- Each status change also keeps its current `log.info`/`warn`/`error` line.
