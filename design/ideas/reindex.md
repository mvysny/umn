# Reindex an index over the existing documents

Brainstorm only (the owner, 2026-11-08): postponed. Came up with issue #8, the `CompositeKey`.

## The problem

`IndexingUMN` indexes a document only when it's written (`D_three_op_log`). A new index, or one
whose `keyGetter` changed, misses every document already stored, and nothing fills it in. So an
app can't move an existing index to a new key type: Aedict's `n_cat_items` and `n_cat_items2`,
keyed by Java-serialized `IdxCategoryOrdinal` and `IdxCategoryItemsBySortKey`, can't become
`CompositeKey` indices (`D_index_key_codec`), because the new index would start empty.

## A shape

`IndexingUMN.reindex(indexName)`: clear the index map, then run the `keyGetter` over every
document (`getSequenceOfIDs()`) and write the keys.

## Open questions

- It needs the database to itself: a write during the walk can be indexed twice, or missed. That
  means an exclusive lock over every mutation, which UMN doesn't have.
- At app startup, the app is frozen for the whole walk, long on a big database. In the
  background, it needs the lock above, and queries in the meantime see a half-built index.
- The app must remember that a reindex is due and that it finished, across a crash halfway
  through: a node-local map, perhaps a version per index.
- The old index map stays behind (`R_mvstore_removemap_leaks`: `clear()` it before `removeMap()`).
