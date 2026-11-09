# Drop an index whose key class is gone

Filed from Aedict (the owner, 2026-11-09), found while planning Aedict's move of two notepad indices
off custom `Serializable` key classes onto `CompositeKey`: `../aedict/design/ideas/upgrade-umn-to-0.18.md`,
`Q_clear_needs_key_class`. Worked out from H2 1.4.198's source; not yet reproduced.

## The problem

0.18's way off a custom key class (`CHANGELOG.md`, README "An index is built once and never
rebuilt"): give the index a new name with a `CompositeKey`, and stop passing the old one, which
"gets its entries cleared". The natural next step is to delete the old key class. That breaks the
database:

- `IndexingUMN.clearDroppedIndices()` opens the old map, `INDEX2_<name>`, with
  `openUtilityMap(…, CompositeKeyDataType())` and calls `clear()`.
- Opening a map reads its root page eagerly: `MVMap.setRootPos()` → `readOrCreateRootPage()` →
  `readPage()`. Reading a page reads every key on it: `CompositeKeyDataType.read()` →
  `ObjectDataType` → `SerializedObjectType`, which Java-deserializes the key.
- So with the class deleted, renamed, moved or obfuscated, the open throws `ClassNotFoundException`
  (or `InvalidClassException`) inside `IndexingUMN`'s constructor, and the app can't open its
  database at all. `clear()` itself would have been fine: per `R_mvstore_removemap_leaks`, it frees
  unread leaves by position and reads only the inner pages. But it never gets that far. The inner
  pages hold keys too.

This is exactly the trap `CompositeKey` exists to get apps out of (`D_index_key_codec`). The way out
still walks through it once.

**Aedict's workaround meanwhile:** it keeps both classes, `@Deprecated`, with their fields and
`serialVersionUID`s untouched, and R8 keeps their names.

## Constraints

- The old index's pages should actually be freed. `MVStore.removeMap(name)` reads no page, but
  `R_mvstore_removemap_leaks` shows it leaves the pages counted live for good, and only an offline
  `MVStoreTool.compact()` gets them back. On a phone that never happens. So `removeMap()` alone is
  out.
- `D_frozen_0_15_formats`: the file format stays as is; an older UMN must still open the file.
- No crash on open, whatever the old map holds. A drop that fails should be logged and retried on
  the next open, not fatal (compare `D_build_index_on_open`'s "failing the open would lock the app
  out of its data").

## Directions

### A. Open the dropped map with a key type that doesn't deserialize

A `DataType` for dropping only, which reads every key as opaque bytes. For a
`TYPE_SERIALIZED_OBJECT` key, `ObjectDataType`'s format looks like a tag, a varint length and the
bytes, so the bytes can be skipped without loading any class. Other tags delegate to
`ObjectDataType` as usual. Then `clear()` frees everything as today. To verify: the exact on-disk
layout per tag, and whether `compare()` gets called while clearing (it shouldn't: no search,
no insert).

### B. Free the pages by position, without reading keys

Walk the page tree from the root position and free every page, the way `removeAllRecursive()`
frees an unread leaf. Inner pages have to be read for their child positions, though, and reading
one reads its keys. So this ends up as A anyway, or as a page parser of our own. Probably A.

### C. `removeMap()` now, reclaim later

Accept the leak at drop time, and let an online compaction reclaim it. But the research says
compaction skips a removed map's pages. Out unless that turns out wrong.

## Open questions

- `Q_reproduce`: a test that writes an index with a custom `Serializable` key class, then drops the
  index while the class can't be loaded (a separate class loader, or bytes naming a class that
  doesn't exist). Does it really fail at open, as the source reading says?
- `Q_opaque_layout`: does every `ObjectDataType` tag have a length you can skip without the class?
  Arrays of serialized objects (a `CompositeKey`'s parts never are, but an old custom key class
  could hold anything), nested types?
- `Q_also_values`: an index map's values are `Array<UUID>`, native, fine. Can any other node-local
  map UMN drops or rewrites hold app classes?
- `Q_changelog`: until it's fixed, the 0.18 README and CHANGELOG should warn not to delete the old
  key class after renaming the index.
