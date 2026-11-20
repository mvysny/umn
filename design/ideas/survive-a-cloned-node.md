# Survive a cloned node

The owner, 2026-11-13, from Aedict: brainstorm first, list the scenarios, reproduce them in one
JVM; then extend the protocol, compatibly if possible, so the proxy server upgrades first and the
apps after. The mirror image of `survive-a-restored-log.md`: there the server's log goes back in
time, here a node does.

## What happened in Aedict

Aedict's Android cloud backup was about to include the UMN file. Its sync smoke test, against
production with a test user, two emulators (Aedict's `design/ideas/allow-backups.md` has the run):

1. Node A, offline, creates a category and two items in it, and a tag: four records in its
   `__OUTGOING_BUFFER`. A's file is copied: the backup.
2. A goes online and pushes the four. A renames the category and deletes item 2.
3. Node B is started from the copy. Its `__CURRENT_LOG_PTR` is where A's was at the copy, and its
   buffer still holds the four records, so it pushes them a second time.
4. The log now reads: create C, create 1, create 2, create T, update C, delete 2, create C,
   create 1, create 2, create T. `MvstoreUMN.create(document, id)` is `putIfAbsent`: the second
   create C is ignored and the rename survives, but **item 2 comes back on every node**, and on
   every node that ever replays this log from 0.

Aedict now keeps the UMN file out of the cloud backup, so the cloud backup can't clone a node; the
sync is the backup. Android's device-to-device transfer still copies the file, as it always has.

## The node's state, and why a clone breaks it

A node is `ROOT2` (the documents), `__CURRENT_LOG_PTR` (how far it pulled) and
`__OUTGOING_BUFFER` (its records the server hasn't ACKed). A copy of the file copies all three.

- **The pointer** is harmless: the clone pulls from where the original stood, and gets everything
  since, the original's pushes included.
- **The buffer** is the trouble: both nodes push the same records, the clone possibly long after
  the original's later changes. `AGENTS.md`'s "applying a `LogRecord` is idempotent" holds for one
  record applied twice in a row; it doesn't hold for a create replayed after its delete, nor for an
  update replayed after a newer one.
  - `DocumentCreated` after a `DocumentDeleted` of the same ID: resurrection.
  - `DocumentUpdated` after a newer `DocumentUpdated`: the newer edit is reverted. (After a
    delete it's skipped: `applyTo` updates only `if (umn.exists(id))`.)
  - `DocumentDeleted` repeated: harmless.

**It doesn't take a clone.** The pusher re-sends every unACKed record on reconnect
(`GlobalLogPusher`). Node A pushes create X; the server appends it, but the connection drops
before the ACK arrives; node B pulls X and deletes it; A reconnects and re-sends create X, which
lands after B's delete. Rarer than a clone, same outcome. `Q_lost_ack_window`.

How a node gets cloned, besides a backup: Android's device-to-device transfer with both phones
still in use; `adb backup`/restore or a root backup app; a user copying the app's data.

## Scenarios to reproduce in one JVM

Two or three `MvstoreUMN` nodes syncing through an in-process log (`MVStoreGlobalLog` or
`InMemoryGlobalLog`); a clone is a copy of a node's MVStore file, or a reopened store over a copied
byte array. Fast enough to run in `umn-core:test`; a battery in `umn-sync-tester` if every
`GlobalLogClient` should pass them.

- `S_resurrect`: the Aedict run above: clone with a buffered create, original deletes; the clone's
  push brings it back.
- `S_revert`: clone with a buffered update, original updates the same document again; the clone's
  push reverts it.
- `S_dup_create`: clone with a buffered create, original only updates it; the second create is
  ignored. Pins today's behaviour.
- `S_dup_delete`: clone with a buffered delete; harmless. Pins it.
- `S_clean_clone`: clone with an empty buffer, both diverge from there; they must converge.
- `S_behind_clone`: clone with an empty buffer and a pointer far behind; pulls everything, pushes
  nothing.
- `S_lost_ack`: no clone: an ACK lost after the append, a sibling's delete, the re-send.
- `S_replay`: a fresh node replays a log already holding create, delete, create of one ID. Every
  fix has to say what this node ends up with, as production's logs may already hold such records.
- `S_mixed_versions`: one of the nodes is 0.15 (`umn-compat-0.15`). A client-side fix leaves it
  resurrecting while new nodes don't: the nodes diverge for good. `Q_divergence_vs_resurrection`.

## Constraints

- `D_frozen_0_15_formats`: no new record or message type, the record bytes, headers and close
  codes stay; a node-local map is allowed.
- **Sync over any server** (a promise): sync logic lives in the client, backends stay thin. A
  server-side filter breaks it, or needs a decision of its own.
- Installed Aedicts run 0.15 and older and never update; anything they push stays as it is.
- Document IDs: Aedict's are all random (`umn.create(document)`). Does any UMN user create a
  document with an ID of its own, delete it and create it again? Then a create after a delete is
  legitimate. `Q_reused_ids`.

## Directions

### A. Node-local tombstones

Applying a `DocumentDeleted` records the ID in a node-local map (`__TOMBSTONES`); a
`DocumentCreated` of a tombstoned ID is skipped, and so is one the node itself would push.

- Fixes `S_resurrect`, `S_lost_ack` and `S_replay` on every new node, from any backend, with no
  wire change.
- Not `S_revert`.
- Old nodes still resurrect: `S_mixed_versions` diverges. Is a node that shows a deleted item
  worse than all nodes showing it? `Q_divergence_vs_resurrection`.
- The map grows by 16 bytes per delete, for good. A user's lifetime of deletes is small; compaction
  could drop tombstones older than … nothing tells when every node has seen a delete.
  `Q_tombstone_growth`.

### B. The app says "I'm a clone", the node rebases its buffer

The app knows when it was restored (Android's `BackupAgent.onRestoreFinished()`, a marker file
outside the backup) and calls, say, `SyncingUMN.restored()`. The node keeps its pointer `P0`,
rewinds to 0 (`forceRestartSync` exists), pulls everything, then **drops every buffered record whose
document ID appears in the log after `P0`**: either the original already delivered it, or someone
changed that document since the copy and the buffered record is stale. What's left was never
delivered and nobody touched since: push it.

- Fixes `S_resurrect`, `S_revert`, `S_dup_*` for clones the app knows about, keeping the clone's
  undelivered changes. No wire change.
- Needs the app to know, which a device-to-device transfer may not tell it, and doesn't cover
  `S_lost_ack`. `Q_detect_clone`: can a node notice by itself?

### C. The server filters

The server reads each pushed record's type byte and ID (fixed offsets in the frozen record bytes;
the document stays opaque) and drops a create of an ID it has seen deleted, ACKing it all the same.

- Covers every client, 0.15 included, from the moment the server upgrades; no divergence between
  versions. Fits "box first".
- Breaks "Sync over any server", and the ACK is positional: the server has to send `OnAdded` for a
  record it didn't append, without the client noticing. `Q_ack_without_append`.
- Not `S_revert`, and not the records already in production's logs (`S_replay`); a one-off purge
  of creates after their own delete could do those.

### D. Combinations

C for the old apps and today, A for logs already polluted and other backends, B for a
restore the app knows about. What's the smallest set that covers what Aedict meets?

## Open questions

- `Q_lost_ack_window`: how wide is the window in `S_lost_ack` in practice: the server appends and
  ACKs in one step, so it takes a connection dying between the two, and a sibling deleting X before
  the reconnect, at most 60 s with `Backoff`. A kill widens it: the node's store loses the ACKs of
  its last second (`survive-a-kill-mid-write.md`), and re-sends those records at its next start,
  however late, a day say.
- `Q_divergence_vs_resurrection`: with a client-side fix, old and new nodes disagree on resurrected
  documents. Better or worse than everyone agreeing on them?
- `Q_tombstone_growth`: keep tombstones forever, or drop them after N months?
- `Q_detect_clone`: a random node ID in a node-local map is copied with the file. Can the node tell
  a clone without the app?
- `Q_ack_without_append`: can the proxy server ACK a dropped record so that 0.15 and HEAD clients
  advance their buffers as if it were appended?
- `Q_reused_ids`: does any UMN user re-create a deleted ID on purpose?
- How many of production's Aedict logs already hold a create after its own delete?
  `../aedict/aedict-common`'s `ProductionLogReplayTest` replays them; count those too.
