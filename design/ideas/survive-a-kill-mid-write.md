# Survive a kill mid-write

The owner, 2026-11-20. H2 1.4.200 opens a store a kill cut short at its last whole commit
(`R_mvstore_kill_opens_empty`), so a kill loses what no commit stored yet: up to `autoCommitDelay`,
1 s, of writes. The proxy server commits each record before ACKing it (`D_commit_before_ack`); a
node's own store is what's left. Android kills a node with SIGKILL: the low-memory killer, often a
swipe from recents.

## What a kill leaves

Lost consistently, the last second of writes is harmless for pulled records (the node re-pulls
them) and costs the user their last second of local edits. The trouble is a pair of writes to two
maps with no commit between: a store may keep the later and lose the earlier, when its walk over
the maps lands just so (`R_mvstore_store_per_map`); a kill before the next store keeps that. Every
such pair on a node:

- **A pulled record, then `__CURRENT_LOG_PTR`.** Fixed: `GlobalLogPuller` saves the pointer only
  right after a commit.
- **The outgoing buffer's first fill, then its `isPopulated` marker.** Fixed: `MutationLog` commits
  the fill before the marker, and drops a stale marker, committed, before a refill. This one wasn't
  a narrow tear: the marker went first, so any store mid-fill kept it.
- **A local edit, then its `__OUTGOING_BUFFER` entry.** Kept without its entry, the edit is never
  pushed: the other nodes never see it, until the document's next edit. The opposite half is
  harmless: the node pushes the record, and applies it again when it comes back from the log.
  Next, below.
- **A document, then its index entries.** Accepted, below.
- **An ACK** (`ackLowest()`, one map). Lost, the record is re-sent after the restart, however late:
  `survive-a-cloned-node.md`'s `S_lost_ack`, with a far wider window.

## Next: commit after each local edit

The owner's call, 2026-11-20: yes, if a commit on a node-sized store costs a millisecond or less.
Measured (`R_mvstore_commit_per_write`), it doesn't on API 22's emulator: 1.4 ms median, p90
3.6–11 ms, and a phone of that age is slower than the emulator's host. So the commit's shape is
open again, `Q_sync_or_background`.

Either way the commit follows the buffer entry's append (`AnnouncingUMN` →
`MutationLog.onRecord()`), so it stores the document and its entry together. Pulled records don't
pass through `AnnouncingUMN`, so only local edits pay. Edits a second or more apart cost no extra
bytes: MVStore's background writer stores each one on its own already.

- **On the editing thread.** The tear needs a kill within that one commit; a local edit that
  returned survives a kill. The edit waits 1.4 ms median on API 22, often the UI thread, which
  then writes to disk. A burst takes 2.2× as long and grows the file 5×: 35 KB an edit, not 7.
- **On a background thread**, edits made while one commit runs sharing the next. The edit waits
  for nothing, and a burst takes about as long as with no commit, at 8–19 KB an edit. The tear,
  and the loss of a returned edit, need a kill within a few milliseconds of the edit: 1.5 ms
  median on API 22 between edits, tens of milliseconds in a burst. The thread is the `Syncer`'s,
  closed with it.

`Q_sync_or_background`: the owner's call. The lean: background, which costs the caller nothing
and turns a second's window into milliseconds; a kill that close to a tap is no swipe.

Rejected:

- **The buffer entry before the document**, write-ahead. A `keyGetter` that throws then leaves a
  record pushed that the node itself can't apply, and 0.18 stalls on its own echo. It also breaks
  `AnnouncingUMN.Listener`'s contract, that the operation is done, and `create(document)` takes its
  ID from the delegate.
- **UMN owning every commit**: auto-commit off, a read/write lock every operation takes, a
  committer storing between operations. Exact, and no cost per write, but `autoCommitDisabled()`
  leaves the commit on a full write buffer on (`R_mvstore_store_per_map`). The app's builder would
  also have to pass `autoCommitBufferSize(0)`, silently wrong if forgotten, and every wrapper
  takes the lock. Too heavy for a tear this narrow.

On graduation: the promise, a returned local edit survives a kill, in `SyncingUMN`'s KDoc and the
README's sync section; a `CHANGELOG.md` line with the cost.

## Accepted: a document and its index entries

The owner, 2026-11-20. The same tear between a document and its index entries isn't healed by
replaying the record: `IndexingUMN.create()` skips an existing document, `update()` diffs against
the stored one, `delete()` returns on a missing one. Writing the index entries first would let a
replayed pulled record heal it, but every reader would see a key ahead of its document, on every
write. The commit after each local edit narrows the local half.

On graduation: one line in `IndexingUMN`'s implementation notes, so nobody "fixes" the order.

## Open questions

- `Q_force_restart_rewind`: `forceRestartSync` rewinds the pointer only at the first connect,
  while `MutationLog` refills the buffer when the `Syncer` is built, and the flag lives in memory.
  Killed in between, the store keeps the refill and the old pointer, and the node never re-pulls
  the new server's log from 0. Rewind in the `Syncer`'s constructor, before `MutationLog`'s commit?
