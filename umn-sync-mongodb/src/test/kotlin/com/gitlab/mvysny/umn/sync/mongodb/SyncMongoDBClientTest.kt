package com.gitlab.mvysny.umn.sync.mongodb

import com.github.mvysny.dynatest.DynaTest
import com.gitlab.mvysny.umn.sync.tester.*
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.MongoDBContainer
import java.util.*

val mongoDBImage = "mongo:4.0"

fun MongoDBContainer.getConnectionUrl(): ConnectionString = ConnectionString(replicaSetUrl)

class SyncMongoDBClientTest : DynaTest({
    if (DockerClientFactory.instance().isDockerAvailable) {
        // we will only start MongoDB once and make every test use a new topic.
        // tests will run much faster this way than if we restarted MongoDB after every test
        // To connect to this MongoDB, just connect to `127.0.0.1:27017`.
        lateinit var dockerizedMongo: MongoDBContainer
        beforeGroup {
            dockerizedMongo = MongoDBContainer(mongoDBImage)
            dockerizedMongo.start()
        }
        afterGroup { dockerizedMongo.close() }

        lateinit var topic: String
        beforeEach { topic = "topic-${UUID.randomUUID()}" }

        val clientProducer: () -> SyncMongoDBClient = {
            val url: ConnectionString = dockerizedMongo.getConnectionUrl()
            val settings: MongoClientSettings = MongoClientSettings.builder().apply {
                applyConnectionString(url)
//        credential(MongoCredential.createCredential("sa", "mydb", "sa".toCharArray()))
                applyToConnectionPoolSettings {
                    it.minSize(0)
                    it.maxSize(1)
                }
            }.build()
            SyncMongoDBClient(settings, "mydb", topic, 20)
        }
        globalLogClientTestBattery(clientProducer)

        syncTestBattery("mvstore", 2, { createMvstoreUMN() }, clientProducer, 2.seconds)

        writerReaderTestBattery("mvstore", 1, { createMvstoreUMN() }, clientProducer)

        syncTestBattery("mvstore", 10, { createMvstoreUMN() }, clientProducer, 2.seconds)

        writerReaderTestBattery("mvstore", 10, { createMvstoreUMN() }, clientProducer)
    }
})
