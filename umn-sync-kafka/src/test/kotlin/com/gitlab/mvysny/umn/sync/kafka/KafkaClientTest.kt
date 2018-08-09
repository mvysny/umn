package com.gitlab.mvysny.umn.sync.kafka

import com.github.mvysny.dynatest.DynaTest
import com.gitlab.mvysny.umn.core.JavaDocumentSerializer
import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.sync.tester.*
import org.apache.kafka.common.TopicPartition
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs Kafka in Docker. To connect to this Kafka, just connect to
 * `127.0.0.1:9092`.
 *
 * @todo mavi this Kafka is very old - find a way to run newest Kafka inside of the Docker
 */
fun Docker.startKafka() = Container("""docker run --rm -d -p 2181:2181 -p 9092:9092 --env ADVERTISED_HOST="127.0.0.1" spotify/kafka""")

class KafkaClientTest : DynaTest({
    if (Docker.isPresent) {
        // we will only start Kafka once and make every test use a new topic.
        // tests will run much faster this way than if we restarted Kafka after every test
        lateinit var dockerizedKafka: Container
        beforeGroup {
            dockerizedKafka = Docker.startKafka()
            println("Waiting 10 secs for Kafka to start")
            Thread.sleep(10000)
            println("Continuing with tests")
        }
        afterGroup { dockerizedKafka.closeQuietly() }

        lateinit var topic: String
        beforeEach { topic = "topic-${UUID.randomUUID()}" }

        val clientProducer = { KafkaClient("127.0.0.1:9092", TopicPartition(topic, 0), JavaDocumentSerializer, AutoTypeResolver) }
        globalLogClientTestBattery(clientProducer)

        syncTestBattery("mvstore", 2, { createMvstoreUMN() }, clientProducer)

        writerReaderTestBattery("mvstore", 2, { createMvstoreUMN() }, clientProducer)
    }
})
