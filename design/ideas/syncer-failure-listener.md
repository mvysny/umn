# Tell the app when the Syncer gives up

Split off `proxy-client-reconnect.md`. Once the `Syncer` reconnects by itself, the only
failure it doesn't retry is `InvalidUsernamePasswordException`: it stops syncing for good and
says so only in the log. The app (Aedict) can't show "wrong password, fix it in settings", and
the user sees sync silently stalled.

Out of scope for 0.16; reconnecting comes first.

## Sketch

- A listener on the `Syncer` (`Syncer.addStatusListener`, or a constructor parameter of
  `UMN.syncing`), called from the syncer's own thread — the app hops to its UI thread.
- Fires on a fatal failure; the app restarts the syncer with new credentials.

## Open questions

- `Q_status_scope`: only fatal failures, or a whole status (connecting / connected / offline,
  retrying in N s / failed)? A status would let Aedict show "offline" too.
- `Q_listener_shape`: a listener interface, or a `StateFlow`-like observable? `umn-core` has
  no coroutines dependency and should stay so on Android.
