[![build](https://github.com/mvysny/umn/actions/workflows/build.yml/badge.svg)](https://github.com/mvysny/umn/actions/workflows/build.yml)

# U.M.N.

The U.M.N. is a NoSQL data storage designed to have the following properties:

* The basic storage interface only offers a very simple key-value storage capabilities
* Designed to be enhanced easily with indices and synchronization capabilities
* Blazingly fast - [H2's MVStore](https://www.h2database.com/html/mvstore.html) storage is 10-100x faster than sqlite3 or any other SQL database.

> The U.M.N. is an acronym of Unus Mundus Network, a reference to a network in the Xenosaga game series.
Literally meaning "One Word", the database is designed to quickly propagate changes across all participants,
ensuring the "One" "truth" across all devices.

UMN currently does not have:

* Support for transactions
* Support for JSON out-of-the-box - it's way faster to store java objects into binary form
  than to JSON.

## Storing objects

UMN stores binary blobs rather than JSON. That's because UMN is required to run on Android phones
where performance is critical. You can use standard Java Serialization for experimenting, but it
breaks as soon as a class changes, so don't use it for real data.

**Recommended: kotlinx.serialization with the ProtoBuf format.**

A synced blob must stay readable both ways: a newer app reads what an older one wrote, and an
older app reads what a newer one wrote, since nodes of different versions sync with each other.
The recommended way to get that is
[kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) with its
[ProtoBuf format](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/formats.md#protobuf-experimental):

* The serializers are generated at compile time by the Kotlin serialization plugin: no
  reflection, which suits Android.
* Protobuf's evolution rules are well documented, and an older reader skips fields it doesn't
  know.
* Give every property an explicit `@ProtoNumber`, never reuse or renumber one, and give every
  property added later a default value, so that blobs written before it existed still decode.
  Remove only a property that has a default value in every released version, or older apps
  can't decode the newer blobs.

UMN ships no kotlinx.serialization adapter. This `DocumentSerializer` is all it takes (it's
`umn-kotlinx-test`'s `KotlinxDocumentSerializerTest`, so it is known to work):

```kotlin
@OptIn(ExperimentalSerializationApi::class)
object ProtoBufDocumentSerializer : DocumentSerializer {
    override fun serialize(document: Any, dout: DataOutputStream) {
        dout.write(ProtoBuf.encodeToByteArray(serializer(document.javaClass), document))
    }

    override fun <T : Any> deserialize(clazz: KClass<T>, din: DataInputStream): T {
        // the document is the stream's last item, with no length in front: read it to the end
        val document = ProtoBuf.decodeFromByteArray(serializer(clazz.java), din.readBytes())
        return clazz.java.cast(document)
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Note(@ProtoNumber(1) val title: String, @ProtoNumber(2) val stars: Int = 0)
```

Read to the end of `din`: UMN writes no length in front of a document, and ProtoBuf doesn't mark
where it ends.

kotlinx.serialization is for documents, never for index keys. An index orders its keys, and
serialized bytes don't sort the way the values do. Key an index by a `String`, a boxed
primitive, a `UUID`, or a `CompositeKey` of them (see [Index](#index)).

The ProtoBuf format is still marked `@ExperimentalSerializationApi`. If you'd rather have blobs
that describe themselves without a schema, kotlinx.serialization's
[CBOR format](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/formats.md#cbor-experimental)
works too, with two differences: build it with `Cbor { ignoreUnknownKeys = true }`, since by
default an older reader rejects fields it doesn't know, and never rename a property, since CBOR
keys fields by name. [Google Protobuf](https://github.com/protocolbuffers/protobuf) itself
works too, at the cost of `.proto` files and generated Java classes.

[JPUtils Box](https://gitlab.com/mvysny/jputils/-/tree/master/box) needs no code generation
and no compiler plugin, and reads the same from Java as from Kotlin: a class writes its values
under keys by hand, so renaming a property never changes the data. An older reader skips keys
it doesn't know, but not a value type that a later Box release added, so store such a type only
once every node runs that release. Box is GPL 3.0, so an app that ships it must be GPL-licensed
as well.

Avoid [Kryo](https://github.com/EsotericSoftware/kryo) for stored or synced data: its default
`FieldSerializer` can't read data written before a class's fields changed. Only its
`TaggedFieldSerializer` and `CompatibleFieldSerializer` are safe there.

## Project Status

The database API is stable at this point and is being battle-tested in the
[Aedict Japanese Dictionary](http://www.aedict.eu/), so you are free to use that.

The syncing part is beta-quality and battle-tested in the Aedict Japanese Dictionary.

### Android Compatibility

UMN runs on Android 5.1 (API 22) or higher: set `minSdk = 22` or higher in your app.
UMN is compiled to Java 17 bytecode, which Android Gradle Plugin 8 or newer desugars for older Android APIs.

## Adding it to your project

Make sure you have the following repository in your `build.gradle`:

```
repositories {
    mavenCentral()
}
```

Then you can add the following dependency:

```
dependencies {
    implementation("com.gitlab.mvysny.umn:umn-core:0.19")
}
```

To open/create a database, use the following code:

```kotlin
data class Person(val name: String, val surname: String) : Serializable

fun main(args: Array<String>) {
    val dbfile = File("/home/mavi/temp/testdb.db")
    // before opening: rewrites a file UMN 0.18 or older wrote, once, if old H2s leaked pages into it
    MvstoreUMN.dropLeakedPages(dbfile)
    val mvstore = MVStore.Builder().compress().fileName(dbfile.path).open()
    
    // beware! Don't use JavaDocumentSerializer nor AutoTypeResolver for production!
    // see those classes's javadoc for more info.
    var umn: UMN = MvstoreUMN(mvstore, JavaDocumentSerializer, AutoTypeResolver)
    // optionally to add indices: umn = IndexingUMN(umn, umnIndices)

    umn.use {
        // creates new person and auto-generates ID for it
        val id: UUID = umn.create(Person(name = "Martin", surname = "Vysny"))
        println(umn.get(id, Person::class))  // will print Person(name = "Martin", surname = "Vysny")
        // deletes the person
        umn.delete(id, Person::class)
    }
}
```

Call `dropLeakedPages()` before every open, not only once: on a file UMN 0.18 or older wrote, H2
1.4.197 and 1.4.198 may have leaked pages, which keep their chunks in the file and keep H2
1.4.200's background writer rewriting the store for as long as it is open. The first call rewrites
such a file; every later one returns within milliseconds. On a desktop's cold start the first call
takes 0.1 s on a clean file and 0.3–1 s on a leaky one of up to 66 MB; a phone may take ten times
that, so on Android make the call off the main thread. For the same reason, open every utility
map your app keeps in the store as soon as you open the store, even one it uses rarely: MVStore
moves only an open map's pages, so the page of a map left closed holds its chunk just the same.

Keep H2's default `autoCommitBufferSize`, which H2 sizes from the heap: 16 MB on a phone's 256 MB
heap. A small buffer costs you silently. While the store sits idle, H2's background writer moves
no chunk larger than the buffer, so a file an older H2 left at several times its live data stays
that size for good. Stores H2 1.4.197 wrote, around 4× their live data, shrank to a median 1.13×
within a few commits at the default buffer, but only to 2.4× at 256 KB. A first sync, which pulls
the user's whole log, also runs about 10× longer at 256 KB.

# Documentation

So you've been using SQL databases, you're accustomed to write SELECTs, creating indices and other
stuff common for SQL databases. Well, NoSQL is something completely different. If you check out
the database API (summed in the [UMN](umn-core/src/main/kotlin/com/gitlab/mvysny/umn/core/UMN.kt) interface),
you'll basically see three methods:

* `create(obj: Any): UUID` which takes a Java Object and saves it into the database under a generated UUID;
* `update(id: UUID, obj: Any)` which takes the UUID and the Java Object and updates it in the database; and
* `delete(id: UUID)` which deletes the object stored under given UUID.

So it's basically a really dumbed-down map from UUID to a Java object. I'll first state why this simplicity
is important, then we'll learn how to create index to look up for stuff.

## Simplicity is key

Everything is a log. When you create new document, a log entry is added into MVStore. When you
update the document, a new log item is appended to the database with the new data, leaving the old
data intact (copy-on-write); the old data is eventually purged by MVStore. This allows for absurd
performance (since we're just adding items to the log) and reliability (in the event of crash,
simply the last non-corrupted log item is discovered in the database file).

However, log also allows easy syncing. If we have but three simple mutation operations (create/update/delete),
we only have three item types in the log. That makes it easy to simply broadcast the log to all
other UMNs out there via any mechanism possible - Kafka (which is a log database itself), Hazelcast,
any NoSQL or even SQL database, even Bittorrent. If we can replay the log in the same order on all other clients
(which is rather easy), we gain syncing capabilities which are reliable.

Simplicity is prerequisite to reliability.

Great. Now how do I search for my data in this 'log' you speak about?

## Index

In UMN, Index serves the following purposes (which may be used separately, or combined):

* A way to quickly find documents based on a key (say, a first name)
* A way to order documents based on a key (say, surname)
* A way to have an extract of a document that you can then show in an UI

### Quick Lookup

Say that we want to look up personnel by first name (doesn't really make sense but let's go with this :)
You need to register an index to the UMN to do that. An index is generally a function that takes
a document and returns a set of keys under which the document needs to be accessible quickly.
In this case, it's the first name:

```kotlin
val index = IndexingUMN.IndexDef.simple<String, Person>("person_firstname", 
    { doc -> setOf(doc.name) }, Person::class)
```

This is an index definition that says that we are going to have a String index (e.g. `"Martin"`)
generated for every document that's a `Person` (remember, the UMN is just a single map which is
a goulash of objects of various types; in UMN terms Java object is the same as document).

Now you need to tell UMN to use this index. That's easy:

```kotlin
umn = IndexingUMN(umn, setOf(index))
```

However, keep in mind:

* Provide the same set of indices every time you open the UMN database.
  * A new index is built over the existing documents when `IndexingUMN` is constructed: the
    constructor walks and deserializes every document once, so that one open takes as long as
    reading the whole database. On Android, open it off the main thread: a dictionary-sized
    database takes seconds, close to the 5-second ANR limit.
  * An index is built once and never rebuilt. To change its indexing function or key type, give it
    a new name; the old name, no longer passed, gets its entries cleared. Its key class may then
    be deleted: clearing reads no key.
  * A document whose indexing function throws during the build is logged and left out of the index.
* The indexing function should work fast, without side effects and must be consistent in providing same results for particular document.
  
> Note: The index occupies space in the MVStore database, however it's a transient utility space which is not
synced over network to other clients. Every client maintains its own indices separately.

Anyway. Now we can simply look up all people named 'Martin':

```kotlin
umn.facet<IndexingUMN>().getIndex(index).findAllDocs(For.Key("Martin"))
```

You can also search for multiple keys at once efficiently; you can even search for a range:

```kotlin
umn.facet<IndexingUMN>().getIndex(index).findAllDocs(For.KeySet("Martin", "Matti", "Ben"))
umn.facet<IndexingUMN>().getIndex(index).findAllDocs(For.KeyRange("A".."F"))
```

Beware: Java `Comparable` is used; the above range lookup will not find "Fredu" because the Java String
"F" is less than the String "Fredu" in Java `Comparable` comparison.

A key is a `String`, a boxed primitive, a `UUID`, a `Date`, a `BigInteger` or a `BigDecimal`, or
a `CompositeKey` of several of them (below). MVStore stores these natively. Any other `Comparable`
class works too, but goes through Java serialization, which writes the class's name and
`serialVersionUID` into the file. Rename or move that class, let R8 obfuscate it, or let its
`serialVersionUID` change, and the database no longer opens. Prefer `CompositeKey`.

### Ordering

The keys in the index are ordered. All lookup functions will preserve this ordering and return the
documents in this order. We can use this to our advantage: say that we want to look up people by
surname, and keep them sorted first by surname, then by first name.

A `CompositeKey` orders by its first part, then by its second, and so on:

```kotlin
val idxSurnameFirstname = IndexingUMN.IndexDef.simple<CompositeKey, Person>("person_surname_firstname",
    { doc -> setOf(CompositeKey.of(doc.surname, doc.name)) }, Person::class)
```

Keep one type per position: parts of different types sort by type, not by value, so an `Int` 5
sorts before a `Long` 3.

We need to register the index to the `IndexingUMN` wrapper:

```kotlin
umn = IndexingUMN(umn, setOf(index, idxSurnameFirstname))
```

Now we can look up all "Vysny" guys, sorted by first name. `For.prefix` matches every key that
starts with the given parts:

```kotlin
val vysny = For.prefix(CompositeKey.of("Vysny"))
val personnel = umn.facet<IndexingUMN>().getIndex(idxSurnameFirstname).findAllDocs(vysny)
```

However that would take up lots of memory if there were lots of matching persons. We can use an
iterator which will lazily fetch the guys for us from the database:

```kotlin
val personnel: Sequence<Pair<UUID, Person>> =
    umn.facet<IndexingUMN>().getIndex(idxSurnameFirstname).getSequenceOfDocs(vysny)
personnel.forEach { (_, person) -> println(person) }
```

### Multiple types of objects

Note that the above indices only consider documents of type `Person` - they will ignore any other types
of documents. In the database there are typically lots of different document types - say
`Department`, `Ticket` etc. To look up instances of those documents, simply define separate indices
for them. This way, even though all documents will be stored in one map, you will be able to look
them up by using appropriate indices.

### Displaying Index In An UI

In your UI, you often you need to show a list of documents, such as articles. In the UI, only a few fields of a document will appear
(e.g. the Title of an Article). Loading all documents into memory, deserializing them and reading the title in order to populate the UI
would be quite an overkill. Instead, create an index whose key is what the UI shows, a `String` or a `CompositeKey`, and list
the index's keys: they're read straight from the index, without loading a single document.

For example, imagine that you have an index that extracts titles from your Articles, and you want to display that in Android's
`ListView`, newest first:

```kotlin
val articleTitleIdxDef: IndexingUMN.IndexDef<String, Article, Article> = IndexingUMN.IndexDef.simple("article_title",
        { doc -> setOf("${doc.created.isoFormat()}: ${doc.title}") }, Article::class)  // an ISO date sorts as text
val index = indexingUMN.getIndex(articleTitleIdxDef)
val titles: List<String> = index.keyPagedList(30, descending = true, dataMissingMarker = "Please refresh")
listView.adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, titles)
```

`keyPagedList()` loads 30 keys at a time as the user scrolls, and keeps only three pages, so this works for any number of
articles. Its size is fixed when it's created, so the `ListView` never sees it change unannounced: after the index
changes, create a new list and notify the adapter. Until then, a key deleted since reads as `"Please refresh"`.
To page by hand, `index.keyCount()` and `index.keyPage(0..29, descending = true)`.

## Facet / Layouts

> This is a nitty-gritty low-level description of the UMN architecture. For a quick start, you don't
need to read this stuff.

The UMN database does not offer all of the functionality in one layer. You start by the actual storage, which
is `MvstoreUMN` (file-backed, or `MvstoreUMN.inMemory()` for tests) and implements the `UMN` interface.

To add support for indices, you wrap existing `UMN` with an `IndexingUMN`. IndexingUMN delegates all calls
to the underlying UMN storage (so that the documents are actually stored) but also updates indices
for every document created, updated or deleted.

To add support for sync, you wrap existing `UMN` with SyncingUMN which delegates all calls to the underlying UMN
storage but also broadcasts the changes as a log to all other clients via a server.

The important thing to keep in mind is to never call methods directly on the `MvstoreUMN` once
it has been wrapped in, say, `IndexingUMN`. If you do, the `IndexingUMN` will not learn of the
changes and the indices will not be updated. Always call methods on the last layout wrapped.

However, if all you have is a SyncingUMN, you can't cast it to `IndexingUMN` to retrieve indices.
That's why there is the `facet()` method which will traverse delegates until `IndexingUMN` (or
any other UMN type) is found.

## Syncing

UMN is able to synchronize the data to all nodes connected to a common _global log_, stored on
some server. UMN Sync converts
all database modifications into a stream of events (called *log records*), and transmits that log over a common server
to all other clients. Every client contains a queue of log records; the queue is emptied as the events
are confirmed to be received by the server.

To activate syncing, you first need to create a client which transmits the log to a particular server of your choosing.
UMN currently syncs via the [UMN Proxy Server](umn-proxy-server), which stores the log in H2 MVStore.
You use the [UMN Proxy Client](umn-proxy-client) which works on any Java 17 including Android.
Another server only needs a thin `GlobalLogClient` implementation, tested with the batteries in
[umn-sync-tester](umn-sync-tester).

### Syncing Example

The easiest way to sync is to launch the UMN Proxy Server in Standalone mode. Please read the
[UMN Proxy Server Documentation](umn-proxy-server) for more info; for experimenting we'll simply launch the server in
docker:

```bash
$ docker run --rm -ti -p 127.0.0.1:10144:10144 -p 127.0.0.1:10145:10145 -v $HOME/db:/var/umn-proxy-server/db mvysny/umn-proxy-server:0.19
```

This will launch the proxy server which will listen on two ports: 10144 is a websocket endpoint for data syncing, 10145 is the admin interface.
Let's create a testing user first:

```bash
$ curl -i -X PUT "localhost:10145/admin/users?username=user&password=example"
```

We will now construct the Proxy Client in your Kotlin code:

```kotlin
val httpClient = OkHttpClient()

fun createProxyClient(): GlobalLogClient {
    val request = Request.Builder().url("ws://localhost:10144")
    return ProxyClient(httpClient, request, "user", "example")
}
```

Let's now wrap your UMN database to add syncing capabilities:

```kotlin
val syncingUMN = umn.syncing({ createProxyClient() }, JavaDocumentSerializer, true)
```

> *Note*: `JavaDocumentSerializer` is really only good for testing purposes, since it uses plain Java Serialization
which is not portable between different Java JDKs (or even different Java versions). You're urged to implement your own
document serializer, e.g. using [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization).

Done. Just remember to use `syncingUMN` to update the database from now on. `syncingUMN` will intercept all
database changes and transmit them to the UMN Proxy Server. It will also apply all changes made by other
nodes.

Every change through `syncingUMN` is committed to the file before the call returns, so an app the
system kills right after keeps it, and later sends it. That costs ~1.4 ms a change on an API 22
emulator, on the calling thread. For many changes in a row, say tagging a hundred notes, wrap them in
`batch`, which commits once, as it returns:

```kotlin
syncingUMN.batch {
    notes.forEach { syncingUMN.update(it.id, it.copy(tags = it.tags + tag)) }
}
```

A batch isn't a transaction: if it throws halfway, the changes it made stay. Until it returns, a
kill may lose them.

When the connection drops, say the phone goes offline, the syncer reconnects by itself with a new
client from `createProxyClient()`, waiting from 1 second up to 1 minute between attempts. Changes
made meanwhile wait in the node's outgoing log. Only a wrong username or password stops the syncer;
`syncingUMN.syncer.stop()` and `start()` it again once the credentials are fixed.

To show the user what the syncer is doing, say "offline" or "wrong password", set a status listener.
It hears the current status first, then every change, on the syncer's own thread:

```kotlin
syncingUMN.syncer.setStatusListener { status ->
    when (status) {
        SyncerStatus.Stopped, SyncerStatus.Connecting, SyncerStatus.Connected -> {}
        is SyncerStatus.Offline -> when (status.cause) {
            is LogRecordNotAppliedException -> {}  // stuck: e.g. "update the app to keep syncing"
            else -> {}                             // reconnects in status.retryInMillis
        }
        is SyncerStatus.Failed -> {}   // wrong username or password
    }
}
```

`syncingUMN.syncer.status` is the current status, and `setStatusListener(null)` removes the listener.

A record from another node that this node can't apply stops syncing at that record, both ways,
until it applies. Typically an older app version has received a document class added by a newer
one. Show that `LogRecordNotAppliedException` cause to the user: an app update unblocks the sync.

### Syncing Limitations

Every node keeps its own outgoing log. This technique keeps all logic client-side which allows for syncing
over literally any database server, be it SQL, NoSQL, Kafka, Hazelcast. However it has a couple of disadvantages and
it is very important to remember those, otherwise you'll get very weird behavior.

It is possible to apply `.syncing` to already existing UMN database. When `.syncing` is applied for the first time,
it will fake the log by creating `CreateRecord` for every document present in the database, and it will transmit those records
to other nodes. However, this is done only once. If you keep using the unwrapped `umn` to store documents (instead of the `syncingUMN`),
the changes will never be picked up by `syncingUMN`  and they will never be transmitted.

It's important to remember that:
- If you used UMN without syncing capabilities in your app, it is OK to add sync support: simply wrap the old UMN
via `.syncing` and keep using only the wrapped instance.
- However, if you decide to remove syncing capabilities by removing the `.syncing` call, any further changes will never be
picked up by the syncer. Never do that, or your node will never sync changes, even if you decide to
re-enable sync later on.

### Switching To Another Global Log

Every `syncingUMN` builds its own mutation log, which it then tries to send to
the global log. Once the log is transmitted to the server it is purged from the client,
to save disk space.
This optimization generally works fine, but it start to bit when you
decide to reconfigure syncing to some other server. Since there is only one
log pointer (there is no separate log pointer for different servers), the new
server will never be repopulated by data from
the clients (because the log pointer already points after the last log item,
and also because the log has been purged). There is however still a possibility to
switch server, but it's experimental and unsupported. Read on.

To switch to another sync server, make sure that the global log stored on that server is empty.
Then, call `UMN.syncing()` function with `forceRestartSync` set to true. That will make
the node throw away the current mutation log, rebuild a new one and send it to the server.
It will also reset the polling pointer and will poll everything from the server - however
the server is empty so this is fine.

The switch is stored by the time `syncing()` returns. A node killed before that may forget it,
and its next start, without the flag, would pull the new server's log from the old server's
position, silently skipping records. So keep your own record of the pending switch, pass
`forceRestartSync` on every start while it's there, and clear it only once `syncing()` has
returned.

Make sure to do this with ONE NODE ONLY - completely destroy other node's database and make
them start from a clean state. That way, the state from one node will propagate to all
other nodes. If the database is not cleaned from other nodes, all nodes will push their
log into the server, resulting in mass log duplication and random data overwrite.

See [Issue #3](https://gitlab.com/mvysny/umn/issues/3) for more info on why it's
impossible to sync to multiple servers with the current UMN architecture.

### Syncing behind a firewall

If you don't want to expose your database directly over the internet, you can use the
[UMN Proxy](umn-proxy-server) to access your database remotely. The UMN Proxy client/server transparently
relies record log commands via websocket communication, and accesses the database of your choice
at the server side. Please see [UMN Proxy Server](umn-proxy-server) for more details.
