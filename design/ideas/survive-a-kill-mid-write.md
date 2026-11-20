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

Commit after the buffer entry is appended (`AnnouncingUMN` → `MutationLog.onRecord()`). The tear
then needs a kill within that one commit, not within a second. And a local edit that returned
survives a kill, which a user notices. Pulled records don't pass through `AnnouncingUMN`, so only
local edits pay; a bulk import pays a commit per document.

- `Q_node_commit_cost`: what does a commit cost on a node's store, megabytes, on a phone?
  `R_mvstore_commit_per_write` measured the proxy's 64 MB log: 280–400 µs and ~26 KB a commit. An
  `R_` once measured, desktop and an API 22 emulator.
- `Q_bulk_edits`: does any app edit in bulk (Aedict importing a category?), and does it then need
  a way out, a commit per batch?

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
