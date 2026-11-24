# Drop an index that has no build state

The owner, 2026-11-24, asked by Aedict. `IndexingUMN` clears an index no longer passed only when
`INDEX_STATE` records it (`D_build_index_on_open`). 0.18 and later record an index at the open
that builds or adopts it. A store that 0.17 or older last opened has `__INDEX2_*` maps but no
state. If an app's first open on 0.18 or later already passes a new index set, the old names were
never recorded: their maps stay in the file for good, never read and never cleared. That breaks
`IndexingUMN`'s own `@param indices` KDoc: "an index left out has its entries cleared". Only a
store that persists can hold such a map: an `InMemoryUMN` never outlives its process.

Aedict's case is its notepad's two Java-serialized indices, re-keyed to `CompositeKey` under new
names (Aedict's `design/ideas/upgrade-umn-to-0.18.md`, step 2). A phone going from Aedict 3.54.0
(UMN 0.15) straight to that release keeps both old maps, one Java-serialized key per notepad
item. That's several MB on the largest notepads, an estimate. On 2026-11-24, 92% of installs
were still on 3.54.0 or older. Until UMN handles it, Aedict plans to call
`umn.dropUtilityMap("INDEX2_<old name>")` on every open, which ties it to the private
`indexMapName()` prefix; its production code has no such call yet. The call stays harmless after
the fix: `dropUtilityMap()` of a missing map does nothing.

## The plan: clear every index map not passed, recorded or not

On open, `IndexingUMN` lists the `INDEX2_*` utility maps and clears each one whose name it wasn't
passed, as it clears a recorded one today. Apps need no code, and the KDoc holds as written.
`INDEX2_` has been the prefix since 0.1, so no older index-map format is left in the 0.9+ files
UMN opens; `INDEX_STATE` doesn't match it.

- **`clearDroppedIndices()` gets simpler.** It drops each name, of the listed `INDEX2_*` maps and
  the recorded names, that isn't passed, then deletes those names' state, so passing one again
  builds it anew. Listing finds a map whatever its state, so two pieces go: the commit between
  the drop and the state removal (a store landing between them leaves either for the next open
  to find), and keeping a failed drop's state for the retry (the map is still listed next open).
  `indexState`'s KDoc loses "an index's entries never outlive its state, the only way
  `clearDroppedIndices` finds them".
- **A new abstract `UMN.utilityMapNames()`**, like `dropUtilityMap()`: in `MvstoreUMN` MVStore's
  `getMapNames()`, filtered by the `__` prefix and stripped of it; in `InMemoryUMN` its maps'
  names, for tests only; a pass-through in `IndexingUMN` and `AnnouncingUMN`, while `SyncingUMN`
  gets one from `UMN by delegate`. That's a `Breaking:` line for a `UMN` implemented outside, as
  `dropUtilityMap()` was in 0.19. Why not a default `delegate?.utilityMapNames() ?: emptySet()`,
  which breaks nothing: a store implemented outside that forgets the override sweeps nothing,
  silently.
- **On every open, not once per store.** An unrecorded map arises only at a store's first open on
  0.18 or later, but a once-only sweep needs a marker kept somewhere, and the sweep costs little:
  1.4.200's `getMapNames()` walks the meta map from `name.` and stops where the prefix ends, one
  entry per map.
- **Reserved names, listed on `openUtilityMap()`.** Listing reserves the `INDEX2_` prefix: an
  app's own utility map named `INDEX2_…` would be cleared. Names are already reserved without
  saying so: `INDEX_STATE`, `CURRENT_LOG_PTR`, the `MutationLog`'s `OUTGOING_BUFFER` and
  `OUTGOING_BUFFER-marker`, and today any `INDEX2_x` once `x` was recorded. So `openUtilityMap()`
  lists them all, not `INDEX2_` alone. That's hard enough to miss: `UtilityMap`'s KDoc already
  says "Should not be used by UMN clients", and Aedict's production code opens no utility map;
  only its tests do.

## Rejected

- **An explicit drop**: `IndexingUMN.dropIndex(umn, name)` or a `dropped: Set<String>` constructor
  parameter, clearing an index's map and state by its name. It's smaller and changes no interface,
  but the app keeps listing old names for as long as a pre-0.18 store may open: practically
  forever. An `IndexDef(replaces = …)` naming the old index has the same flaw.
- **Finding `MvstoreUMN` through `facet<MvstoreUMN>()`** and listing its MVStore's maps, with no
  interface change: `IndexingUMN` would reach into MVStore, against the dumb core.

## Not this idea

Two `IndexingUMN`s over one store share `INDEX_STATE`, so each clears the other's indices and
both rebuild on every open. Listing doesn't make it worse. A separate idea: an invariant line,
"one `IndexingUMN` per store", like the `Syncer` one, or a `facet` check in the constructor.

## On graduation

- `D_build_index_on_open`: "a name no longer passed is cleared" then covers an unrecorded one too,
  found by listing; the why-nots above (an explicit drop, `facet`, a once-only sweep, a default
  `utilityMapNames()`) go there.
- `openUtilityMap()`'s KDoc lists the reserved names; `indexState`'s loses its ordering sentence.
- A test: `DroppedIndexTest`'s `GoneKeyWriter` case without its `__INDEX_STATE["gone"] = true`
  line, which is Aedict's case: no state, and a Java-serialized key whose class may be gone.
  Closer to it than a `umn-compat-0.15` run, which can't delete the key class.
- A CHANGELOG line, and a `Breaking:` one for `utilityMapNames()`; Aedict drops its
  `dropUtilityMap("INDEX2_…")` calls once it runs the release.
