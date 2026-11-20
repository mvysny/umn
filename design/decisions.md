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
no query planner, and a changed index definition takes a new index name (`D_build_index_on_open`).

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

## D_index_key_codec — Why is a composite index key a `CompositeKey` rather than the app's own class or a codec?

An index map (`__INDEX2_*`) keeps MVStore's `ObjectDataType` keys: `String`, the boxed primitives
and `UUID` natively, any other class with Java serialization, whose bytes carry the class's full
name and `serialVersionUID`. Rename, move or obfuscate the class and the store fails to open:
Aedict hit it with `IdxCategoryOrdinal` and pinned the UID. `ObjectDataType` also stores an
`Object[]` of native parts natively, ordered part by part (`R_mvstore_object_array_keys`), so
{CompositeKey} is that array: no class name in the file, and bytes 0.15 reads. A key →
`ByteArray` codec must instead preserve order byte by byte (sign bits, `UUID`'s signed halves,
terminated strings); a naive one, protobuf say, returns wrong ranges silently. A `DataType` per
index would put H2 into `IndexDef`. Java-serialized keys stay allowed: files already hold them,
and moving an index to `CompositeKey` takes a new index name (`D_build_index_on_open`). `K` stays a bare
`Comparable`; a non-serializable key fails on its first write.

## D_reconnect_in_syncer — Why does the Syncer reconnect rather than the GlobalLogClient?

0.15's {GlobalLogClient} had to reconnect by itself; {ProxyClient} never did, and sync stalled
until the app restarted the {Syncer}. A client that reconnects must also resume pulling where the
node left off and re-send what was never acked, but its `rewind` is fixed at
`setReceiverCallback` and the outgoing buffer belongs to the syncer — every backend would redo
the sync engine's bookkeeping, against **Sync over any server**. So one client is one connection,
dead for good once it fails, and the syncer replaces client, puller and pusher together: the
puller recomputes the rewind, the pusher re-sends every unacked record. Delivery is therefore
at-least-once: a duplicate is harmless, a {LogRecord} being idempotent, while a skipped record
is a lost change. The cost: acks and log pointers are positional, so a closed pusher or puller
must ignore its old client's late callbacks, or they move under the new one.

## D_empty_javadoc_jars — Why publish near-empty javadoc jars rather than Dokka's KDoc?

Maven Central demands a `-javadoc.jar` beside every jar but not its content, so `publish()` keeps
`withJavadocJar()`: empty in a Kotlin module, in `umn-core` only the Java `PasswordHash`. IDEs
read KDoc from the `-sources.jar`, which is what matters. Dokka would fill the jar, and
javadoc.io would render it, but its HTML bundles fonts, megabytes per module, while Central caps
uploads at 80 MB a month, all releases together: three modules a release, and a 0.16, 0.16.1,
0.16.2 burst eats much of it; today a whole release is about 0.3 MB. Online KDoc, if ever
wanted: Dokka's HTML to GitLab Pages from CI, outside the quota. Watch the JDK too: javadoc
bundles web fonts from JDK 23 on, 4.3 MB in `umn-core`'s jar, so `publish()` passes
`--no-fonts` there.

## D_own_compaction_timer — Why does the proxy server compact its log on its own timer rather than MVStore's?

MVStore's auto-compaction makes an idle store walk every chunk ten times a second
(`R_mvstore_idle_compaction`): 7.6 % of production's one CPU on a 4 GB log, growing with it. So
{MVStoreGlobalLogProvider} opens the store with `autoCompactFillRate(0)` and calls
`MVStore.compact()` once a minute instead, the same 40 % target and write budget, a six-hundredth
of the walks. Why not compaction off for good: partly dead chunks would never be rewritten, and the
file, 3.4 GB of chunks for 1.47 GB live, would grow faster than the log. Why not a longer
`autoCommitDelay`: it widens the window of acknowledged records a crash loses. Why not a
read/write lock around the timer: `compact()` is a concurrent path H2 already runs beside writers
(`R_mvstore_compact_concurrency`), and H2's own commits would stay outside our lock anyway; it would
only let a compaction stall client pushes. The one hazard is shutdown: the timer stops, never
interrupted, before the store closes.

## D_compact_on_startup — Why does the proxy server rewrite its log on every start?

Online compaction (`D_own_compaction_timer`) keeps the log only 40 % full, and can't reclaim the
logs `deleteUser()` leaked before 0.18 (`R_mvstore_removemap_leaks`): production's 3.8 GB held
755 MB of live data. So {StartupCompaction} copies the closed log with `MVStoreTool.compact()`, a
byte-exact passthrough, checks the entry counts, forces the copy to disk (H2 syncs nothing) and
renames it over the log (`R_mvstoretool_compact`). Any failure, or too little disk, starts on the
log as it was. The cost: a start as long as the copy, 20 s on production's log; and the copy's
store header names no chunk, so until H2 first reuses space a kill mid-write leaves a log H2 opens
empty (`R_mvstore_kill_opens_empty`), which {EmptiedLogGuard} refuses. Why not only when
it pays: the fill rate can't see leaked pages, and a heuristic is harder to reason about than a
constant cost on a rare deploy. Why not an operator command: nobody remembers it. Why not online:
the copy needs the file closed. A rollback stays possible: the 0.12 server reads the copy, held so
by `ServerUpgradeTest`. `compactDatabaseOnStartup: false` turns it off.

## D_build_index_on_open — Why does IndexingUMN build a new index when constructed rather than in the background?

A new index starts empty, and `update()` of an unchanged document registers no key, so only a walk
over every document fills it. {IndexingUMN}'s constructor walks once for every index not yet built:
no other thread holds the instance yet, so no write races the walk and no query sees a half-built
index. The cost: the first open after adding an index blocks for the walk, paid once. The state lives in the node-local `INDEX_STATE`: `false` before the
walk, `true` after, each behind a commit (`R_mvstore_store_per_map`), so a crash redoes the walk.
An index from before `INDEX_STATE` is adopted when non-empty, not rebuilt: Aedict's `migrateV1V2()`,
run after the constructor, types V1 documents by their old index entries. A document whose keys
throw is logged and skipped: failing the open would lock the app out of its data. An index once
built is never rebuilt, so a changed `keyGetter` or key type takes a new name; a name no longer
passed is cleared, through `UMN.dropUtilityMap()`, which decodes no key, so the old key class may
then be deleted (`R_mvstore_clear_reads_no_key`). A clear that fails is logged and retried on the
next open, for the same reason. Accepted: an older app version doesn't maintain an index it doesn't know, so
after a downgrade and upgrade that index misses the documents created meanwhile.
