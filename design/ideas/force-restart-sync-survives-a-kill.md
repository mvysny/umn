# forceRestartSync survives a kill

The owner, 2026-11-20, left over from making a node survive a kill mid-write (`R_mvstore_store_per_map`,
`D_commit_per_local_edit`).

`forceRestartSync` does two things at two times. The `Syncer`'s constructor refills the outgoing
buffer (`MutationLog`), committed; the first connect rewinds the log pointer to 0
(`GlobalLogPuller`), which the next store keeps, up to a second later. The flag itself lives in
memory: the app passes it once, when it switches servers.

Killed in between, the store keeps the refill and the old server's pointer. The next start, without
the flag, pushes the documents to the new server but pulls the new server's log from the old
position, skipping every record below it, silently.

- `Q_force_restart_rewind`: rewind the pointer in the `Syncer`'s constructor, before `MutationLog`'s
  commit, so the refill and the rewind are stored together? The puller then needs no
  `forceRestartSync` at all.
