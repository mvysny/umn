# Survive a server log restored from a backup

Brainstorm only (the owner, 2026-10-30): not for today. It comes from Aedict Online's backups,
`../aedict/aedict-online/README.md`, "Backups".

## The problem

A user's log, `{user}__log`, restored from a backup is shorter than it was. A node that synced
after the backup keeps a position (`__CURRENT_LOG_PTR`) past the restored log's end:

- The server streams from the client's `rewind` (`MVStoreGlobalLogClient.onNewItems()`,
  `keyIterator(currentLogPointer)`), and `append()` writes at `lastKey() + 1`. Every record
  written after the restore lands below that node's position, so the node never receives it.
- The node's own changes since the backup were acked, so its pusher never sends them again.
  They're gone from the log, and the user's other nodes never see them.
- Neither side notices. The node and the log just diverge, silently and for good.

0.16 and 0.17 don't change this. Aedict's installed apps run 0.15 and older and never update
(`D_frozen_0_15_formats`), so a fix that needs a new client covers only the apps that update.

## Constraints

`D_frozen_0_15_formats`: no new record or message type, and the headers, close codes and admin
REST stay as they are. A node-local map is allowed. Whatever reaches an old node has to be
something it already understands.

## Directions

### A. The server pads the log up to the client's position

On connect, the server compares `rewind` with the log's length. If `rewind` is past the end,
it first appends no-op records up to `rewind`, then streams as usual. Records written after the
restore then land past every position, and every node receives them.

- **Server-only, so it covers old apps too.** It needs only an upgraded server.
- A null record already means a hole on the wire (`OnLogRecord(null)`, which the puller counts
  without applying), but a client older than 0.15 can't read one (`Messages.kt`). Safer:
  `DocumentDeleted` of a random UUID. `MvstoreUMN.delete()` is `map.remove()`, a no-op for a
  missing ID. `Q_noop_record`.
- **Not enough alone.** A node that connects late finds the log already grown past its
  position by its siblings, and silently skips the records between. Without an identity per
  node, the server can't tell it from a node that synced after the restore. `Q_late_node`.
- It doesn't recover the node's own changes since the backup: those are still lost.

### B. A log identity

The server keeps a random ID per user log, and a restore gives the log a new one (an admin REST
call, or automatically whenever the server opens a database whose log is shorter than the
lengths it last saw, `Q_detect_restore`). A new client stores the ID in a node-local map
(`__LOG_ID`), and on a mismatch it rewinds to 0 and re-pushes its documents.

- It catches the late node of A, since the ID changed whatever the length.
- **How the ID reaches the client** without a new message type: as a response header on the
  websocket upgrade, which 0.15 clients ignore. Is a new header within
  `D_frozen_0_15_formats`, which freezes "headers"? `Q_wire`.
- `SyncingUMN` already rewinds with `forceRestartSync`; Aedict never passes it.
- **Re-push**: replaying the log from 0 restores the log's state, but a document the node
  changed after the backup has to be pushed again, or it stays on this node alone. Pushing every
  document as `DocumentUpdated` heals the log; a sibling's newer edit loses to the older copy,
  last write wins. Push only what the node changed since the backup? The node doesn't know when
  that was. `Q_repush_scope`.
- Covers only apps that update.

### C. A and B together

A keeps old apps receiving new records; B makes new apps converge fully. Old apps still lose
their own changes since the backup, and a late old node still skips records. That much loss is
inherent: an old client has no way to learn the log went back.

### D. Operational only

No code change: the restore procedure tells users to clear the app's data, or to reinstall.
That covers every version, and nobody will do it.

## Open questions

- `Q_noop_record`: does a `DocumentDeleted` of an unknown ID stay a no-op on every client still
  in use, 0.13 to 0.15, and in Aedict's listeners? Does applying a remote record ever land in the
  node's outgoing buffer, echoing back?
- `Q_late_node`: can the server tell nodes apart without a new header? It sees only
  `username`, `password` (shared by all of a user's nodes) and `rewind`.
- `Q_detect_restore`: should the server find out by itself (a node-local high-water mark per user,
  kept outside the restored file) or does the admin say so?
- `Q_wire`: is an upgrade response header allowed by `D_frozen_0_15_formats`, or does it need a
  decision of its own?
- `Q_repush_scope`: all documents, or a subset?
- Is this worth it at all, against backing up often and accepting the loss?
