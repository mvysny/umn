package com.gitlab.mvysny.umn.sync.kafka

import com.github.mvysny.dynatest.DynaTest
import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.sync.tester.*
import org.apache.kafka.common.TopicPartition
import java.util.*

val kafkaDockerImage = "spotify/kafka"
/**
 * Runs Kafka in Docker. To connect to this Kafka, just connect to
 * `127.0.0.1:9092`.
 *
 * @todo mavi this Kafka is very old - find a way to run newest Kafka inside of the Docker
 */
fun Docker.startKafka() = Container(kafkaDockerImage, """docker run --rm -d -p 2181:2181 -p 9092:9092 --env ADVERTISED_HOST="127.0.0.1" $kafkaDockerImage""")

class KafkaClientTest : DynaTest({
    if (Docker.isPresent && false) {  // disable Kafka temporarily, doesn't work from Android anyway and the test runs too long
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

        val clientProducer = { KafkaClient("127.0.0.1:9092", TopicPartition(topic, 0)) }
        globalLogClientTestBattery(clientProducer)

        syncTestBattery("mvstore", 2, { createMvstoreUMN() }, clientProducer)

        writerReaderTestBattery("mvstore", 2, { createMvstoreUMN() }, clientProducer)
    }
})
