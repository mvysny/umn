# UMN Syncing with MongoDB

Provides UMN syncing capabilities, using a [MongoDB](https://www.mongodb.com/) server to store the log.

## Tutorial

Run the MongoDB instance. To run a testing MongoDB quickly instance in Docker, just type:

```
$ docker run --rm -ti -p 27017:27017 mongo:4.0
```

The mongodb will not require any username/password (the security is disabled); also
when you kill MongoDB by pressing Ctrl+C, the log and the whole database is gone.
Ideal for testing ;)

To add syncing capabilities to your existing UMN database:

```kotlin
val umn = ...
val url = ConnectionString("mongodb://localhost:27017/mydb")
val settings = MongoClientSettings.builder().apply {
    applyConnectionString(url)
//        credential(MongoCredential.createCredential("myuser", "mydb", "mypassword".toCharArray()))
    applyToConnectionPoolSettings {
        // the sync can be slow; let's limit the amount of connections so that the server
        // is not overwhelmed.
        it.minSize(0)
        it.maxSize(1)
    }
}.build()
val syncingumn = umn.syncing({
    SyncMongoDBClient(settings, "mydb", "mycollection", JavaDocumentSerializer, YourTypeResolver, 10000)
}, JavaDocumentSerializer, true)
// the sync is now started. You must use syncingumn from now on - if you add documents to
// the original umn the changes will not be propagated to syncingumn.

syncingumn.syncer.start()
syncingumn.syncer.stop()

syncingumn.close()
```

A proper capped collection is created
automatically by `SyncMongoDBClient`; you can specify the size of the collection in
`SyncMongoDBClient` constructor, by default it's 1TB.

## Security

Every user should sync to its own private database. Configure MongoDB to add users and
assign databases to them; then just use `MongoCredential` to supply username/password
to the UMN MongoDB client.

## Issues

* Should work on Android without any issues: https://stackoverflow.com/questions/6887887/mongodb-on-android
* Unfortunately Change streams are supported only on replicas; to keep the server setup simple
  `SyncMongoDBClient` does not use change streams for listening on changes on the server,
  but instead it uses active polling.
* TODO: Add support also for change streams when there's a MongoDB replica available.
