# Drop an index whose key class is gone

Filed from Aedict (the owner, 2026-11-09), found while planning Aedict's move of two notepad indices
off custom `Serializable` key classes onto `CompositeKey`: `../aedict/design/ideas/upgrade-umn-to-0.18.md`,
`Q_clear_needs_key_class`. Worked out from H2 1.4.198's source; reproduced by `DroppedIndexTest`
(2026-11-09), red until the fix lands.

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
- So with the class deleted, renamed, moved or obfuscated, the open throws
  `IllegalArgumentException("Could not deserialize …")`, caused by `ClassNotFoundException` (or
  `InvalidClassException`), inside `IndexingUMN`'s constructor, and the app can't open its
  database at all. `clear()` itself reads only the inner pages (`R_mvstore_removemap_leaks`), but
  it never gets that far, and the inner pages hold keys too.

This is exactly the trap `CompositeKey` exists to get apps out of (`D_index_key_codec`). The way out
still walks through it once.

**Aedict's workaround meanwhile:** it keeps both classes, `@Deprecated`, with their fields and
`serialVersionUID`s untouched, and R8 keeps their names.

## Constraints

- The old index's pages should actually be freed. `MVStore.removeMap(name)` reads no page, but
  `R_mvstore_removemap_leaks` shows it leaves the pages counted live for good, and only an offline
  `MVStoreTool.compact()` gets them back. On a phone that never happens. So `removeMap()` alone is
  out, and so is reclaiming later: compaction skips a removed map's pages.
- `D_frozen_0_15_formats`: the file format stays as is; an older UMN must still open the file.
- No crash on open, whatever the old map holds. A drop that fails should be logged and retried on
  the next open, not fatal (compare `D_build_index_on_open`'s "failing the open would lock the app
  out of its data").

## The fix: clear the dropped map through a key type that never decodes

`clear()` needs only the child positions of the inner pages, and a page's layout puts those
before the keys. H2 1.4.198, `Page.read()` / `Page.write()` **[src]**:

- **An inner page** is header, child positions and counts, then the keys, and nothing after them:
  values live on leaves only. So a key type that swallows the rest of the buffer as one blob
  loses nothing `clear()` uses.
- **A leaf** is header, keys, values. `NonLeafPage.removeAllRecursive()` frees an unloaded leaf by
  its position alone (`map.removePage(c, mem)`), never reading it. The one leaf that does get read
  is a root leaf, at open, so the value type must swallow too, or it would read the key bytes as
  values. Both types opaque: a root leaf reads as one blob, then nothing.
- **`MVMap.clear()`** swaps in an empty root and calls `removeAllRecursive()`. No search, no
  insert, so no `compare()`.

H2 already has this type: `MVStoreTool.GenericDataType` reads the remaining buffer as one
`byte[]`, and its `compare()` throws, a tripwire should `clear()` ever compare. It is
package-private: copy its ~30 lines, as an internal `OpaqueDataType` in `umn-core`. Then:

```kotlin
delegate.openUtilityMap<String, Any>(indexMapName(name), OpaqueDataType, OpaqueDataType).clear()
```

As far as the source shows, 1.4.198 keeps no key or value type in a map's metadata, so opening
with other types changes nothing on disk. The test confirms it.

And apart from the type: each drop gets its own `try/catch`, which logs and keeps that index's
`INDEX_STATE` entry, so the next open retries it. That covers a corrupt page and anything else no
one thought of.

### Rejected

- **A page parser of our own**, freeing the tree by position: the opaque type gets the same with
  H2's own `removeAllRecursive()`.
- **An opaque type that skips `ObjectDataType`'s tags one by one** (the first draft of this
  idea): the keys sit last on an inner page, so their layout never matters, nested arrays and
  all.

## The test

`umn-core`'s `DroppedIndexTest`, on a file store, since an in-memory store never reads a page
back. Both cases fail today exactly as above: `IndexingUMN.<init>` → `clearDroppedIndices()` →
`MVStore.openMap()` → `readOrCreateRootPage()` → `CompositeKeyDataType.read()` →
`ClassNotFoundException`. The fill-rate checks after it haven't run yet, so their thresholds
(over 90 % before, under 70 % after) are untested.

- Write `__INDEX2_gone` straight through MVStore, keyed by a `Serializable` test class whose
  serialized bytes name a class that doesn't exist (same-length name, patched in), with
  `INDEX_STATE` saying `gone` is built. A precondition reads the map back with
  `CompositeKeyDataType` and expects the deserialize failure, so the fixture really is unloadable.
- Two cases: enough entries for three levels (48 keys a page: 5 000 entries), so
  `removeAllRecursive()` reads inner pages below the root; and a single root leaf.
- Then open an `IndexingUMN` without `gone`: it opens, `INDEX_STATE` forgets `gone`, and the
  pages are freed. Measure that as `R_mvstore_removemap_leaks` did, with `getCurrentFillRate()` at
  `retentionTime` 0, and another map's pages interleaved in the same chunks, or a wholly dead
  chunk would be dropped from the count and lift the rate back up.

## Open questions

- `Q_also_values`: an index map's values are `Array<UUID>`, native, fine. Can any other node-local
  map UMN drops or rewrites hold app classes? The same opaque type would cover it.
- `Q_changelog`: until it's fixed, the 0.18 README and CHANGELOG should warn not to delete the old
  key class after renaming the index.
