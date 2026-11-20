# Survive a kill mid-write

The owner, 2026-11-20, after `KillTest` found it: guard first, then fix the header. A kill that
cuts short a chunk at the end of the file opens the whole store empty while the store header names
no chunk (`R_mvstore_kill_opens_empty`), and every `StartupCompaction` leaves the header so. Done:
{EmptiedLogGuard} refuses such a log and names the byte to truncate it at. Open: keep the log from
getting there at all.

## A. The compacted copy's header names its last chunk

H2 writes the header for a chunk stored short of the file's end, and on `rollbackTo()`. Candidates:

- `compactMoveChunks()` on the copy before it's swapped in: it moves chunks to the file's start,
  so some land short of the end. Unverified that the header then names one, nor at what cost on a
  755 MB log.
- One more commit placed short of the end: no public lever for it in 1.4.198.

Whatever writes the header must keep `D_frozen_0_15_formats`: a header naming a chunk is the
ordinary state, which 0.15 reads. The reproducer, disabled until then: `EmptiedLogGuardTest`'s
"a compacted log cut short still opens with its records".

Even then, the header names the copy's last chunk only: a kill later restores up to where the
`next` chain from it breaks, not necessarily the last commit. Is that enough?

## B. Repair instead of refusing

The guard knows the byte. Truncating there, after keeping the file as `umn_proxy_log.emptied`, would
start the server unattended, losing only the cut write. Why the guard refuses for now: the event is
rare, and the walk assumes every chunk since the header lies at the end in version order, true in
every file seen so far but not proven.

## C. Android nodes

`MvstoreUMN` runs the same H2. A new node's store names no chunk until H2 reuses space, and Android
kills apps with SIGKILL. A node opened empty re-pulls the log from 0 and loses only its unsynced
`__OUTGOING_BUFFER`, and its `close()` then truncates the file. Unverified on a device; the guard
covers the proxy server only. Installed 0.15 apps never update.

## Open questions

- How often does production's proxy log reuse space, so how long is it exposed after a start?
- Does a proxy server ACK a record before the commit that stores it, so that a kill loses an ACKed
  record however H2 recovers?
