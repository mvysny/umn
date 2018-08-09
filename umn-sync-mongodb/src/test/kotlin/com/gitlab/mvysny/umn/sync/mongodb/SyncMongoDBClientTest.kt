package com.gitlab.mvysny.umn.sync.mongodb

import com.github.mvysny.dynatest.DynaTest
import com.gitlab.mvysny.umn.core.JavaDocumentSerializer
import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.sync.tester.*
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import java.util.*

/**
 * Runs MongoDB 4.0 in Docker. To connect to this MongoDB, just connect to
 * `127.0.0.1:27017`.
 */
fun Docker.startMongoDB() = Container("""docker run --rm -d -p 27017:27017 mongo:4.0""")

class SyncMongoDBClientTest : DynaTest({
    if (Docker.isPresent) {
        // we will only start Kafka once and make every test use a new topic.
        // tests will run much faster this way than if we restarted Kafka after every test
        lateinit var dockerizedKafka: Container
        beforeGroup { dockerizedKafka = Docker.startMongoDB() }
        afterGroup { dockerizedKafka.closeQuietly() }

        lateinit var topic: String
        beforeEach { topic = "topic-${UUID.randomUUID()}" }

        val clientProducer = {
            val url = ConnectionString("mongodb://localhost:27017/mydb")
            val settings = MongoClientSettings.builder().apply {
                applyConnectionString(url)
//        credential(MongoCredential.createCredential("sa", "mydb", "sa".toCharArray()))
                applyToConnectionPoolSettings {
                    it.minSize(0)
                    it.maxSize(1)
                }
            }.build()
            SyncMongoDBClient(settings, "mydb", topic, JavaDocumentSerializer, AutoTypeResolver, 50)
        }
        globalLogClientTestBattery(clientProducer)

        syncTestBattery("mvstore", 2, { createMvstoreUMN() }, clientProducer)

        writerReaderTestBattery("mvstore", 1, { createMvstoreUMN() }, clientProducer)
    }
})
