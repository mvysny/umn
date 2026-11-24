# Drop an index that has no build state

The owner, 2026-11-24, asked by Aedict. `IndexingUMN` clears an index no longer passed only when
`INDEX_STATE` records it (`D_build_index_on_open`). 0.18 and later record an index at the open
that builds or adopts it. A store that 0.17 or older last opened has `__INDEX2_*` maps but no
state. If an app's first open on 0.18 or later already passes a new index set, the old names were
never recorded: their maps stay in the file for good, never read and never cleared. That breaks
`IndexingUMN`'s own `@param indices` KDoc: "an index left out has its entries cleared".

Aedict's case is its notepad's two Java-serialized indices, re-keyed to `CompositeKey` under new
names (Aedict's `design/ideas/upgrade-umn-to-0.18.md`, step 2). A phone going from Aedict 3.54.0
(UMN 0.15) straight to that release keeps both old maps, one Java-serialized key per notepad
item. That's several MB on the largest notepads, an estimate. On 2026-11-24, 92% of installs
were still on 3.54.0 or older. Until UMN handles it, Aedict calls
`umn.dropUtilityMap("INDEX2_<old name>")` on every open, which ties it to the private
`indexMapName()` prefix.

## Options

- **Clear every index map not passed, recorded or not** (the lean): on open, `IndexingUMN` lists
  the `INDEX2_*` utility maps and clears each one whose name it wasn't passed, as it clears a
  recorded one today. Apps need no code, and the KDoc holds as written. It needs the map names: a
  new `UMN.utilityMapNames()`, in `MvstoreUMN` MVStore's `getMapNames()` (in 1.4.200) filtered by
  the `__` prefix, in `InMemoryUMN` its maps' names, and a pass-through in each wrapper. That's a
  `Breaking:` line for a `UMN` implemented outside, as `dropUtilityMap()` was in 0.19. The cost on
  every open is a pass over the meta map's names, which is small.
- **An explicit drop**: `IndexingUMN.dropIndex(umn, name)` or a `dropped: Set<String>` constructor
  parameter, clearing an index's map and state by its name. It's smaller and changes no interface,
  but the app keeps listing old names for as long as a pre-0.18 store may open: practically
  forever.

## Open questions

- `Q_reserved_prefix`: listing makes `INDEX2_` a reserved prefix, since an app's own utility map
  named `INDEX2_…` would be cleared. Say so on `openUtilityMap()`, or is that too easy to miss?

## On graduation

- `D_build_index_on_open`: "a name no longer passed is cleared" then covers an unrecorded one too.
- A test: a store a 0.15 node wrote (`umn-compat-0.15`), or `DroppedIndexTest` with a hand-made
  `__INDEX2_x` map and no state, opened without `x`: the map is gone.
- A CHANGELOG line; Aedict drops its `dropUtilityMap("INDEX2_…")` calls once it runs the release.
