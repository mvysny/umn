# Decisions

Why this project is the way it is and not otherwise — FAQ-shaped: each entry is a question and
its current answer. Rewrite the answer when it changes; delete the entry when nobody asks any
more. An entry is earned by what it would cost to reverse — half the code base — or by research
the next person would otherwise redo (cited as its `R_`). Not an entry: windows → panels
"because that's the trend", this red over that red, `get_foo` over `is_foo?`, the testing library,
the CI host, a version bump — a comment at the site of the choice, or nothing; nothing about
`design/` itself. Cite by slug, `D_<slug>`, never by position; `grep '^## D_' design/decisions.md`
is the index. An entry is as long as its question needs: every road not taken keeps all of its
losing reasons. When you have written an entry, re-read it against the one above for consistency,
and open the doc comments it touches and delete what they already say.

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

## D_frozen_0_15_formats — Why freeze the 0.15 wire format both ways but the disk format one way?

Installed Aedict apps never all update, and a proxy server upgrades on its own schedule, so any
release may sync with any other, 0.15 included; a wire migration never helps, the other side
can't run it. Frozen both ways: the {LogRecord} bytes, the proxy {AbstractMessage} bytes, headers
and close codes, and the admin REST paths and JSON. A new capability may add a node-local map,
never a record or message type: 0.15 throws on an unknown type byte, and its sync stops for good.
A file only moves forward: Aedict and Aedict-Reader each keep their own, a cloud restore or a
device transfer lands it under the latest app, and Android won't install an older app over a
newer one. So a release opens every file 0.9 or later wrote — the MVStore format, the map names
(`ROOT2`, `__OUTGOING_BUFFER`, `__CURRENT_LOG_PTR`, `__INDEX2_*`, `users`, `{user}__log`) and
their key/value encodings — migrating it in place if they changed, losing nothing (Reader syncs
nowhere, so its file is the only copy); older code never opens the result. Nor does an older proxy
server: its owner fixes a bad release forward, and H2 relies on that, as 1.4.200 trusts a
clean-shutdown flag that 1.4.198, writing in between, leaves stale
(`R_mvstore_clean_flag_handover`). Why not both ways on disk, as through 0.18: it pins H2 to 1.4.x
for good (`R_h2_reads_one_format`), to guard a hand-back that doesn't happen. The cost: H2 brings no migration, so leaving 1.4.x ships a
relocated 1.4 reader for as long as an install may jump from a 1.4-era release, and a proxy
server's only way back from a bad release is forward. The API may change freely: adapting the app
is easy, while a sync or storage mismatch shows up as silently diverging data.
`umn-compat-0.15`'s tests hold HEAD to it.

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
wanted: Dokka's HTML to GitHub Pages from CI, outside the quota. Watch the JDK too: javadoc
bundles web fonts from JDK 23 on, 4.3 MB in `umn-core`'s jar, so `publish()` passes
`--no-fonts` there.

## D_own_compaction_timer — Why does the proxy server compact its log on its own timer rather than MVStore's?

MVStore's auto-compaction rewrites and moves chunks in the background whenever it projects the
file under 90 % full, walking every chunk on its wake-ups to find out (`R_mvstore_idle_compaction`);
on 1.4.198 the walk alone took 7.6 % of production's one CPU, idle, on a 4 GB log. So
{MVStoreGlobalLogProvider} opens the store with `autoCompactFillRate(0)` and calls
`MVStore.compact()` once a minute instead, at a 40 % target and the store's write budget. The
writer still walks once after each write, 1–2 ms per 100 000 chunks, which {StartupCompaction}
keeps few. Why not compaction off for good: partly dead chunks would never be rewritten, and the
file, 3.4 GB of chunks for 1.47 GB live, would grow faster than the log. Why not a
read/write lock around the timer: `compact()` is a concurrent path H2 already runs beside writers
(`R_mvstore_compact_concurrency`), and H2's own commits would stay outside our lock anyway; it would
only let a compaction stall client pushes. The one hazard is shutdown: the timer stops, never
interrupted, before the store closes.

## D_commit_before_ack — Why does the proxy server commit each record before ACKing it rather than leave it to MVStore's auto-commit?

The ACK drops the record from the node's `__OUTGOING_BUFFER`, and the broadcast moves the user's
nodes' `__CURRENT_LOG_PTR` past it. Killed before the auto-commit, up to `autoCommitDelay` later,
the server lost the record for good and gave its key to the next one, which those nodes then
skipped; 1.4.200 recovers only up to the last commit (`R_mvstore_kill_opens_empty`). So
{MVStoreGlobalLogClient} commits after each append, and a broadcast stops at the user's committed
end, never carrying another client's record mid-commit. The cost is a chunk per record
(`R_mvstore_commit_per_write`): a node pushing a long offline queue takes ~0.3 ms and ~26 KB of
file a record, freed after the retention time; pushes from different connections share commits.
Why not group commit: ACKs and broadcasts would move to a shared committer thread, where one slow
client's blocking send stalls every user.

## D_commit_per_local_edit — Why does a node commit each local change before it returns rather than leave it to MVStore's auto-commit?

A kill, which Android sends to reclaim an app's memory, loses what no commit stored: up to
`autoCommitDelay`, 1 s, of the user's changes. Worse, a store landing between a change's document
and its outgoing record may keep the document alone, which the node then never pushes
(`R_mvstore_store_per_map`). So {MutationLog} commits after appending each record, storing both.
The cost (`R_mvstore_commit_per_write`): ~1.4 ms a change on API 22's emulator, on the calling
thread, often the UI's. Changes a second apart write no more bytes, as MVStore's own writer stores
each alone anyway; a burst grows the file 5× and takes 2.2× as long, hence {batch}, which commits
once as it returns. Why not commit on a background thread, which costs the change nothing and lets
a burst's changes share commits: a change that returned could still be lost, if only for
milliseconds, and one change's milliseconds are well accepted, a burst having {batch}. Why not
append the record before writing the document, so that a tear leaves only a record the node
applies again: a `keyGetter` that throws would leave pushed a record the node itself can't apply,
and the sync stalls on it; {AnnouncingUMN.Listener} promises the change is already made; and
`create(document)` takes its ID from the delegate. Why not let UMN own every commit, auto-commit
off and each change under a lock the committer waits on: exact, and free per change, but
`autoCommitDisabled()` leaves the full-buffer commit on (`R_mvstore_store_per_map`), so the app's
builder would also have to pass `autoCommitBufferSize(0)`, silently wrong if forgotten, and every
wrapper would take the lock.

## D_refill_rewinds — Why does every refill of a node's outgoing buffer rewind its log pointer rather than `forceRestartSync` rewinding it?

A refill is for a new server: `forceRestartSync` fills the buffer with every document for a server
that has none of them, and whose log the node must pull from 0. Stored without the rewind, the
refill makes the next start push to the new server but pull its log from the old one's position,
skipping every record below it for good. Refill and pointer live in two maps, which MVStore stores
map by map (`R_mvstore_store_per_map`), so no order of writes stores them together; one stored fact
has to imply the other. That fact is the buffer's marker: `forceRestartSync` only removes it and
commits, and whenever it is missing {MutationLog} refills and the {Syncer}'s `onPopulate` clears
the pointer, both before the commit that precedes the marker. A kill anywhere leaves the marker
missing, and the next start redoes both, or stored, both stored before it. A first sync rewinds a
pointer that isn't there: the puller writes one only after a commit, which stores the marker, and
0.9 already wrote the marker. Why
not rewind where the pointer is read, as the first puller connects: until the next store, a second
later or whenever the app starts the syncer, a kill keeps the refill and the old pointer. Why not
rewind on the flag in the constructor, before the marker's removal commits: a store between the two
writes still keeps one without the other, and a pointer cleared without the refill leaves the new
server without this node's documents. What stays open is the flag itself, in memory until that
first commit, so the app passes it until `syncing()` returns.

## D_compact_on_startup — Why does the proxy server rewrite its log on every start?

Online compaction (`D_own_compaction_timer`) keeps the log only 40 % full, and can't reclaim the
logs `deleteUser()` leaked before 0.18 (`R_mvstore_removemap_leaks`): production's 3.8 GB held
755 MB of live data. So {StartupCompaction} copies the closed log with `MVStoreTool.compact()`, a
byte-exact passthrough, checks the entry counts, forces the copy to disk, renames it over the log
and syncs the directory (`R_mvstoretool_compact`). Any failure, or too little disk, starts on the
log as it was. The cost: a start as long as the copy, 20 s on production's log. Why not only when
it pays: the fill rate can't see leaked pages, and a heuristic is harder to reason about than a
constant cost on a rare deploy. Why not an operator command: nobody remembers it. Why not online:
the copy needs the file closed. `compactDatabaseOnStartup: false` turns it off.

## D_node_auto_compaction — Why does a node's store compact itself rather than through a UMN call?

A node opens its store with the default `autoCompactFillRate`, so MVStore's background writer
rewrites and moves chunks whenever it projects the file under 90 % full; it compacted a
100 000-chunk test store within seconds (`R_mvstore_idle_compaction`). So {MvstoreUMN} offers no
compaction. Why not one on the app's start: a full rewrite blocks the start for as long as it
runs, and a phone app can't wait. Why not on a timer beside the sync: the writer already does
that, and a call can't do better, since freeing the emptied chunks at once would pull them from
under readers on an older version (`R_mvstore_compact_concurrency`). The proxy server turns
auto-compaction off for its gigabyte log on one CPU (`D_own_compaction_timer`); a node's store is
megabytes, its walk cheap. Why not measure its battery cost on a phone: phones ran 1.4.198, whose
writer walked every chunk on every wake-up; 1.4.200 walks on a subset of them
(`R_mvstore_idle_compaction`). The cost: nothing checks that a real node's store stays small.

## D_build_index_on_open — Why does IndexingUMN build a new index when constructed rather than in the background?

A new index starts empty, and `update()` of an unchanged document registers no key, so only a walk
over every document fills it. {IndexingUMN}'s constructor walks once for every index not yet built:
no other thread holds the instance yet, so no write races the walk and no query sees a half-built
index. The cost: the first open after adding an index blocks for the walk, paid once. The state lives in the node-local `INDEX_STATE`: `false` before the
walk, `true` after, each behind a commit (`R_mvstore_store_per_map`), so a crash redoes the walk.
An index with no state, as every index of a store 0.17 or older last opened, is built like a new
one. Why not adopt a non-empty one as built: its holes, stale keys and the documents a crash left
unindexed, would stay for good. A document whose keys
throw is logged and skipped: failing the open would lock the app out of its data. An index once
built is never rebuilt, so a changed `keyGetter` or key type takes a new name; a name no longer
passed is cleared, through `UMN.dropUtilityMap()`, which decodes no key, so the old key class may
then be deleted (`R_mvstore_clear_reads_no_key`). A clear that fails is logged and retried on the
next open, for the same reason.
