# Reindex an index over the existing documents

Brainstormed with the owner, 2026-11-08; the shape below is settled, not yet built. Came up with
issue #8, the `CompositeKey`. Wanted by Aedict Reader, whose `key-analyses-by-created-millis`
idea moves its analyses from `tameta_name` to a new `CompositeKey` index.

## The problem

`IndexingUMN` indexes a document only when it's written (`D_three_op_log`). A new index, or one
whose `keyGetter` changed, misses every document already stored, and nothing fills it in. So an
app can't move an existing index to a new key type: Aedict's `n_cat_items` and `n_cat_items2`,
keyed by Java-serialized `IdxCategoryOrdinal` and `IdxCategoryItemsBySortKey`, can't become
`CompositeKey` indices (`D_index_key_codec`), because the new index would start empty. Nor can
the app fill it with `update()` of each document: an unchanged document registers
`register - unregister`, nothing.

## The shape

No new public API: the app defines the new index, and `IndexingUMN`'s constructor fills it.

- A node-local utility map, `INDEX_STATE`, marks each index that's built: name → `true`.
- In the constructor, every index without the mark: clear its map (a run a crash cut short left
  part of it), then one walk over every document (`delegate.getSequenceOfIDs()`), for all such
  indices at once, registering each document's keys through `registerUnregisterIndices(valueId,
  keys, ∅)`, never `create`/`update`. Then mark it built.
- Safe in the constructor: no other thread holds the `IndexingUMN` yet, so no write races the
  walk and no query sees a half-built index. The cost is the open, once: a few hundred documents
  in Aedict Reader, maybe tens of thousands of `Box` deserializations in Aedict's notepad.
- Changing an index's `keyGetter` or key type means a new name (`n_cat_items` → `n_cat_items3`).
  A `version` on `IndexDef` only if renames get annoying.
- Nodes rebuild their own: the walk writes no document, so no `LogRecord`, and `INDEX_STATE` is
  one more node-local map 0.15 ignores (`D_frozen_0_15_formats`).

Decided:

- **An index from before the mark existed** has none: a non-empty one is adopted as built, an
  empty one filled. Not rebuilt: Aedict's `migrateV1V2`, run after the constructor, types a V1
  document (still in `ROOT`, with `ROOT2` empty) by the old index maps, which a rebuild would
  clear. The cost: holes in an adopted index stay, such as Aedict Reader's stale keys and the
  documents a crash left unindexed (`IndexingUMN`'s KDoc).
- **A `keyGetter` that throws during the walk:** log at error and skip the document, which stays
  unindexed as before; the open succeeds and the index is marked. Failing the open would leave
  the app unable to read its own database until an update, worse than the sync stall
  `LogRecordNotAppliedException` makes.
- **A marked index no longer in the `IndexDef` set:** `clear()` its map and drop the mark. An
  index is derived data, so passing it again later rebuilds it. `clear()`, since a removed map's
  pages are never reclaimed (`R_mvstore_removemap_leaks`); `UtilityMap` has no remove anyway.

- **An index retired in the same release that brings the mark** was never marked, so it's never
  cleared: Aedict Reader's `tameta_name`, say. `UMN` lists no utility maps to find it by. Accepted:
  the map stays, and it's small.

## Open questions

- Verify before relying on it: MVStore stores a version as a whole, so a stored mark implies
  the walk's index writes before it are stored too.
- An older app version (a downgrade) doesn't maintain an index it doesn't know, yet the mark
  stays: documents it creates are missing from that index after the next upgrade. Android
  doesn't downgrade an app in normal use; document it and accept.

## On graduation

- `D_three_op_log`'s "an index definition change does not reindex existing documents" becomes:
  a new index name fills itself; a changed `keyGetter` needs a new name.
- `IndexingUMN`'s KDoc: the `indices` warning about new indices not being reindexed.
- README and `CHANGELOG.md`: the first open after adding an index takes a walk over every
  document; and the rename rule.
