# Park a record that fails to apply, rather than stall the sync on it

The owner, 2026-11-09: 0.18's stall is too harsh. A record that fails to apply stops the whole sync
until it applies, outgoing changes included, and the user notices. A missed record is the lesser
penalty. This is for 0.19.

**On hold, the same day.** A transient failure recovers by itself, and a deterministic one can be
fixed quickly with an app update. The app can make sure its `keyGetter`s never throw. Aedict's replay of
production's 710 logs (2.26 M records) under 0.18 failed on none. Revisit if a real stall shows up.

## What 0.18 does

`GlobalLogPuller` catches the failure and leaves the log pointer on the record. The `Syncer`
drops the connection, reports `Offline(LogRecordNotAppliedException)` and reconnects with
`Backoff`, which doubles from 1 s up to 60 s. Every reconnect pulls from the same pointer and fails
on the same record. `pusher.push { puller.failure == null }` stops pushing as soon as that happens,
so this node's own changes barely go out either (ce3ca63, `CHANGELOG.md` 0.18).

- **A transient failure recovers by itself**, an `IOException` from the storage say, or a full
  disk: the next reconnect, at most 60 s later, applies it. So "stalls for good" is wrong for
  those.
- **A deterministic failure stalls for good**, unless an app update makes the record apply: a
  `keyGetter` that throws on this one document, a document that doesn't deserialize, a document
  class this app version doesn't know. If no update ever fixes it, that user's device stops syncing
  in both directions, permanently, and reconnects to the server every minute as long as the app
  runs.

The case for stalling, from ce3ca63: a skipped record is a lost change on that node (`D_reconnect_in_syncer`:
"a skipped record is a lost change"), and a node never diverges if it waits. That's right for one
case, **a document type from a newer app version**: once this app updates, the record applies and
nothing was lost. Skipping it would have lost it for good. It is wrong for everything else.

**What clients did before 0.18:** the in-process clients skipped the record. `ProxyClient` didn't:
the exception escaped into okhttp's `onMessage`, which failed the websocket. 0.16/0.17's `Syncer`
then reconnected every second, from the same pointer, and failed again. That was a stall too, only
louder and with no cause. 0.15's `ProxyClient` never noticed the lost connection
(`D_reconnect_in_syncer`), so sync was dead until the app restarted, then died on the same record
again. So for Aedict, the only real user, a bad record has always stalled the sync. 0.18 made the
stall visible and slower, not stricter. Worth confirming against the 0.15 sources before relying on
it: `Q_015_stalled_too`.

## The proposal: park, keep going, retry the parked ones later

A record that still fails after a few quick retries is **parked**. It goes into a node-local
utility map (`PARKED_RECORDS`: log index → the record's bytes, plus the error for diagnostics). The
pointer moves past it and the sync carries on in both directions.

- **Per-document order is kept.** Once a document ID has a parked record, every later record for
  that ID is parked behind it, even one that would apply. Otherwise a parked `DocumentCreated`
  retried later would resurrect a document a later `DocumentDeleted` removed, and a skipped
  `DocumentUpdated` (it applies only `if exists`) would be lost under it. Records only ever touch
  one whole document (`D_three_op_log`), so per-ID order is all the order there is. Indices span
  documents, but UMN enforces no cross-document invariant.
- **Retry the parked records** on every `Syncer.start()`, and so after every app update, which
  restarts the process. Retry them in log order, a whole ID's chain at a time. A chain that applies
  is removed. A chain that fails stays parked, cheaply, until the next start.
- **No failure classification.** Transient errors go away during the quick retries or the next
  start; a newer app's document type applies once the app updates; a deterministic bug stays parked
  and costs the user only that one document, not their sync.
- **The app sees it**: say `Syncer.parkedCount`, and a `SyncerStatus.Connected(parked = n)` or a
  separate listener. Aedict could show "N changes from your other devices need a newer Aedict" in
  the sync settings, not as an error.
- **Frozen formats hold** (`D_frozen_0_15_formats`): a node-local map, nothing new on the wire or
  in the server's log. An old node never sees any of it.

What it costs over 0.18: the node shows that one document stale, or missing, until its chain
applies. That's the "missed record" the owner prefers, and it's no longer silent or permanent.

## Alternatives

- **Back to skipping** (pre-0.18 in-process behaviour). Simplest, but a newer app's document type is
  lost on that node for good, and nothing tells the app.
- **Stall, but keep pushing.** Decouple the pusher from the puller's failure, so the node's own
  changes still go out. Better than 0.18, but incoming stays stuck for good on a deterministic bug,
  and the server still gets a reconnect a minute.
- **Stall for N attempts or T hours, then skip.** It recovers a transient failure and loses the
  rest, and still stalls for hours. Parking dominates it.

## Open questions

- `Q_015_stalled_too`: did 0.15's `ProxyClient` really die on an apply failure? Read its
  `onMessage` in the 0.15 sources (`umn-compat-0.15` pulls them). If so, the history above holds,
  and with it the argument that parking is only ever gentler than what Aedict users ever had.
- `Q_quick_retries`: how many quick retries before parking, and how fast: three, a second apart?
  They should outlast a GC pause or a storage hiccup, nothing longer.
- `Q_parked_index_state`: a parked `DocumentCreated` whose `keyGetter` throws leaves nothing
  written (0.18 runs the `keyGetter` first), so the indices stay consistent. Check that a parked
  `DocumentUpdated` leaves the old version indexed as it was.
- `Q_parked_forever`: does a chain that never applies get dropped after a while, or does it stay
  parked for good? It's bytes in a node-local map, so keeping it costs little and leaves a
  diagnosis possible. Lean: keep it.
- `Q_parked_and_forceRestartSync`: every refill of the outgoing buffer, `forceRestartSync`'s
  included, rewinds the pointer to 0, in the `Syncer`'s `onPopulate` (`D_refill_rewinds`). Clear
  `PARKED_RECORDS` there too, since every record comes again.
- Aedict's data: `../aedict/aedict-common`'s `ProductionLogReplayTest` replays production's logs
  under 0.18 and counts the failures. How many users that is decides how urgent 0.19 is.
