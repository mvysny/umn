package com.gitlab.mvysny.umn.sync.mongodb

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.MongoCredential
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.model.CreateCollectionOptions
import org.bson.Document
import java.io.Closeable
import kotlin.concurrent.thread


fun main(args: Array<String>) {
    // run MongoDB in Docker first:
    //  docker run --rm -ti -p 27017:27017 mongo:4.0
    val url = ConnectionString("mongodb://localhost:27017/mydb")
    val settings = MongoClientSettings.builder().apply {
        applyConnectionString(url)
//        credential(MongoCredential.createCredential("sa", "mydb", "sa".toCharArray()))
        applyToConnectionPoolSettings {
            it.minSize(0)
            it.maxSize(1)
        }
    }.build()
    MongoClients.create(settings).use { client ->
        println("Got client")
        val db = client.getDatabase("mydb")
        println(client.listDatabaseNames().toList())
        if (!db.listCollectionNames().contains("mycollection")) {
            db.createCollection(
                "mycollection",
                CreateCollectionOptions().capped(true).sizeInBytes(1_000_000_000_000_000L)
            )
        }
        val collection = db.getCollection("mycollection")
        thread(isDaemon = true) {
            try {
                // Change streams are only available on replica: https://stackoverflow.com/questions/48139224/mongodb-change-stream-replica-set-limitation
                // so either you need to have a complex setup, or a non-recommended setup of one-node replica, or we can't use change streams
                // instead of forcing the user to use complex setup, we'll use active polling
                var current = 0
                while (true) {
                    collection.find().skip(current).iterator().use { iterator ->
                        while (iterator.hasNext()) {
                            println("WATCHER: " + iterator.next()["foo"])
                            current++
                        }
                    }
                    Thread.sleep(300)
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
        var current = collection.countDocuments()
        Thread.sleep(500)
        collection.insertOne(Document("foo", "bar${current++}"))
        Thread.sleep(500)
        collection.insertOne(Document("foo", "bar${current++}"))
        Thread.sleep(500)
        println(collection.find().toList().map { it["foo"] })
    }
}

fun MongoClient.asCloseable(): Closeable = Closeable { this@asCloseable.close() }
fun MongoClient.use(block: (MongoClient)->Unit) = asCloseable().use { block(this@use) }
