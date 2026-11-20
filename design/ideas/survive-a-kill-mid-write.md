# Survive a kill mid-write

The owner, 2026-11-20, after `KillTest` found that a kill mid-chunk could open the whole store
empty. H2 1.4.200 opens such a store at its last whole commit (`R_mvstore_kill_opens_empty`): the
proxy server's log and a node's store alike, Android included. Installed 0.15 apps keep 1.4.197 and
its empty open, and never update. What a kill can still lose:

- `Q_ack_before_commit`: does the proxy server ACK a record before the commit that stores it? Then
  a kill loses an ACKed record however H2 recovers: the node has dropped it from
  `__OUTGOING_BUFFER`, so it never reaches another node. Neither `MVStoreGlobalLog` nor the proxy
  server calls `commit()`: MVStore's auto-commit stores within `autoCommitDelay`, 1 s by default.
