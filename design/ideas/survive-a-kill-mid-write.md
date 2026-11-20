# Survive a kill mid-write

The owner, 2026-11-20. H2 1.4.200 opens a store a kill cut short at its last whole commit
(`R_mvstore_kill_opens_empty`), so a kill loses what no commit stored yet: up to `autoCommitDelay`,
1 s, of writes. The proxy server commits each record before ACKing it (`D_commit_before_ack`); a
node's own store is what's left.

- `Q_node_pointer_ahead`: the puller applies a record, then advances `__CURRENT_LOG_PTR`, with no
  commit between. A stored chunk may hold the later write to one map without the earlier to
  another (`R_mvstore_store_per_map`): a kill then leaves the pointer past a record the node never
  stored, skipped for good. As narrow as one store's walk over the maps; worth a commit per pulled
  record (`R_mvstore_commit_per_write`)? The same question for a local edit and its
  `__OUTGOING_BUFFER` entry.
