# Decisions

Why this project is the way it is and not otherwise — FAQ-shaped: each entry is a question and
its current answer. Rewrite the answer when it changes; delete the entry when nobody asks any
more. An entry is earned by what it would cost to reverse — half the code base — or by research
the next person would otherwise redo (cited as its `R_`). Not an entry: windows → panels
"because that's the trend", this red over that red, `get_foo` over `is_foo?`, the testing library,
the CI host, a version bump — a comment at the site of the choice, or nothing; nothing about
`design/` itself. Cite by slug, `D_<slug>`, never by position; `grep '^## D_' design/decisions.md`
is the index. The first entry is the ruler: every later one trims to its length — which is how
long this file gets, so keep it short. When you have written an entry, re-read it against the one
above, open the doc comments it touches and cut what they already say, then cut the fat.

---

## D_three_op_log — Why a UUID → blob map with three mutations rather than a queryable store?

UMN was built for the Aedict dictionary on Android, where it must be fast and must sync across
a user's devices. {UMN} is a map from UUID to a serialized document, mutated only by `create`,
`update` and `delete`; MVStore appends each write to its copy-on-write log. Three mutations mean
three {LogRecord} types, so the change history is a log any transport can carry and any node can
replay in global order — that is the whole sync design, and it is why backends stay thin
(**Sync over any server**). Indices, full-text and sync are wrappers, each keeping its own state
in node-local utility maps. Why not SQL or sqlite: 10-100× slower than MVStore, and a query
language has no replayable shape. Why not JSON documents: binary serialization is faster on a
phone, so the format is the caller's {DocumentSerializer}. The cost we carry: no transactions,
no query planner, and an index definition change does not reindex existing documents.

## D_frozen_0_15_formats — Why freeze the 0.15 disk and wire formats rather than migrate them?

Aedict-Online (a 0.15 node and admin client) and its sync server (a 2018 0.13-SNAPSHOT build)
are not upgraded in step with the app, and Aedict-Reader must open what 0.15 wrote, so a phone
on 0.16 talks to that server and may hand its file back to 0.15 code. A migration never helps:
the other side can't run it. Frozen are the MVStore file format (stay on h2-mvstore 1.4.x:
format 1, the same `ObjectDataType` tags; 2.x can't), the map names and key/value encodings
(`ROOT2`, `__OUTGOING_BUFFER`, `__CURRENT_LOG_PTR`, `__INDEX2_*`, `users`, `{user}__log`), the
{LogRecord} bytes, the proxy {AbstractMessage} bytes, headers and close codes, and the admin
REST paths and JSON. A new capability may add a node-local map, which 0.15 ignores, but never a
record or message type: 0.15 throws on an unknown type byte, and its sync stops for good. The API may change freely: adapting the app is easy, while a sync
or storage mismatch shows up as silently diverging data. `umn-compat-0.15`'s tests hold HEAD to it.

## D_index_key_codec — Why do custom index keys go through Java serialization rather than a codec of their own?

An index map (`__INDEX2_*`) is opened without a key type, so MVStore's `ObjectDataType` writes
the keys: `String`, the boxed primitives, `UUID` and a few more natively, any other class with
Java serialization. Those bytes carry the class's full name and `serialVersionUID`; rename, move
or obfuscate the class and the store fails to open — Aedict hit it with `IdxCategoryOrdinal` and
pinned the UID. A codec per index (an `IndexDef` handing a `DataType` to `openUtilityMap()`)
helps new indices only: an existing index keeps its bytes (`D_frozen_0_15_formats`), and moving
it needs the reindex `D_three_op_log` lacks. It also adds another `org.h2` type to the API. So
`K` stays a bare `Comparable`, as in 0.15: a `Serializable` bound would steer users onto the
fragile path, while a non-serializable key still fails on its first write.
