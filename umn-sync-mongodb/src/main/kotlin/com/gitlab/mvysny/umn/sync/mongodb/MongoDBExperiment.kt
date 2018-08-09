package com.gitlab.mvysny.umn.sync.mongodb

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import java.io.Closeable


fun main(args: Array<String>) {
    // run MongoDB in Docker first:
    //  docker run --rm -ti -p 27017:27017 mongo:4.0
    MongoClients.create().use { client ->
        val db = client.getDatabase("mydb")
        println(client.listDatabaseNames().toList())
    }
}

fun MongoClient.asCloseable(): Closeable = Closeable { this@asCloseable.close() }
fun MongoClient.use(block: (MongoClient)->Unit) = asCloseable().use { block(this@use) }
