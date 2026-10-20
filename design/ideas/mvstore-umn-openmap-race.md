# MvstoreUMN may open one utility map twice

Split off the proxy server's missed-delivery bug (fixed in `MVStoreGlobalLog`, see its private
`openMap()`). H2 MVStore 1.4.x `openMap()` isn't atomic while the map doesn't exist yet: two
threads creating one map each get a map with an id of its own, the name ends up bound to one of
them, and what was written through the other is orphaned — gone after a reopen.

`MvstoreUMN` opens its root maps in the constructor (one thread), but `MvstoreUtilityMap`
opens `__$name` whenever `openUtilityMap()` is called, from any thread. Two threads opening the
same utility map for the first time would split it — e.g. an index or the sync's
`CURRENT_LOG_PTR` / `OUTGOING_BUFFER`.

Not seen happening: today each utility map is first opened by one component on one thread.

## Open questions

- `Q_worth_it`: synchronize `MvstoreUMN.openUtilityMap()` on the store (cheap, one lock per
  open), or document "open a utility map from one thread first" on `UMN.openUtilityMap()`?
- `Q_newer_mvstore`: does a later MVStore make `openMap()` atomic? The version is pinned for
  `D_frozen_0_15_formats`, so this only matters if the pin ever moves.
