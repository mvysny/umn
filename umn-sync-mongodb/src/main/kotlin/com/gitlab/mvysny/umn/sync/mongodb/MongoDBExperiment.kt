package com.gitlab.mvysny.umn.sync.mongodb

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.model.CreateCollectionOptions
import org.bson.Document
import java.io.Closeable
import java.util.*


fun main(args: Array<String>) {
    // run MongoDB in Docker first:
    //  docker run --rm -ti -p 27017:27017 mongo:4.0
    MongoClients.create().use { client ->
        val db = client.getDatabase("mydb")
        println(client.listDatabaseNames().toList())
        if (!db.listCollectionNames().contains("mycollection")) {
            db.createCollection(
                "mycollection",
                CreateCollectionOptions().capped(true).sizeInBytes(1_000_000_000_000_000L)
            )
        }
        val collection = db.getCollection("mycollection")
        collection.insertOne(Document("foo", "bar${UUID.randomUUID()}"))
        println(collection.find().toList())
    }
}

fun MongoClient.asCloseable(): Closeable = Closeable { this@asCloseable.close() }
fun MongoClient.use(block: (MongoClient)->Unit) = asCloseable().use { block(this@use) }
