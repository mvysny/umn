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
        // the sync is performed in the background so it doesn't really have to be fast.
        // Let's limit the amount of connections to the server, so that the server
        // is not needlessly drained of resources.
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

Docker example. To start MongoDB with security enabled, just run:

```
docker run --rm -ti -e MONGO_INITDB_ROOT_USERNAME=root -e MONGO_INITDB_ROOT_PASSWORD=example  -p 27017:27017 mongo:4.0
```

This will create the `root`/`example` user. To create a database and an user, we'll need to run the `mongo` command inside of the docker.

To check out the docker container ID, just run:
```
$ docker ps
CONTAINER ID        IMAGE               COMMAND                  CREATED             STATUS              PORTS                      NAMES
dc9d3e2361b3        mongo:4.0           "docker-entrypoint.s…"   19 minutes ago      Up 19 minutes       0.0.0.0:27017->27017/tcp   quirky_bhabha
```

So MongoDB is running in container named `quirky_bhabha`. Now we can connect to the MongoDB:

```
$ docker exec -ti quirky_bhabha mongo -u root -p example --authenticationDatabase admin
> use mydb
> db.createUser({ user: "myuser", pwd: "example", roles: [{ role: "readWrite", db: "mydb" }] })
> db.getUsers()
[
        {
                "_id" : "mydb.myuser",
                "user" : "myuser",
                "db" : "mydb",
                "roles" : [
                        {
                                "role" : "readWrite",
                                "db" : "mydb"
                        }
                ],
                "mechanisms" : [
                        "SCRAM-SHA-1",
                        "SCRAM-SHA-256"
                ]
        }
]
> exit
```

Now we can connect to our database:

```
$ docker exec -ti quirky_bhabha mongo -u myuser -p example --authenticationDatabase mydb
> use mydb
> db.getCollectionNames()
> exit
```

Now the database is ready; we can connect to it by specifying the connection string of `mongodb://localhost:27017/mydb`
and using the username `myuser` and password `example`.

### Other security considerations

* Use SSL to encrypt traffic
* Read more on [MongoDB Security Checklist](https://docs.mongodb.com/manual/administration/security-checklist/)

## Issues

* Should work on Android without any issues: https://stackoverflow.com/questions/6887887/mongodb-on-android
* Unfortunately Change streams are supported only on replicas; to keep the server setup simple
  `SyncMongoDBClient` does not use change streams for listening on changes on the server,
  but instead it uses active polling.
* TODO: Add support also for change streams when there's a MongoDB replica available.
