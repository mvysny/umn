package com.gitlab.mvysny.umn.sync.kafka

import com.github.mvysny.dynatest.DynaTest
import com.gitlab.mvysny.umn.sync.tester.*
import org.apache.kafka.common.TopicPartition
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.KafkaContainer
import java.util.*

class KafkaClientTest : DynaTest({
    if (DockerClientFactory.instance().isDockerAvailable && false) {  // disable Kafka temporarily, doesn't work from Android anyway and the test runs too long
        // we will only start Kafka once and make every test use a new topic.
        // tests will run much faster this way than if we restarted Kafka after every test
        lateinit var dockerizedKafka: KafkaContainer
        beforeGroup {
            dockerizedKafka = KafkaContainer()
            dockerizedKafka.start()
            println("Waiting 10 secs for Kafka to start")
            Thread.sleep(10000)
            println("Continuing with tests")
        }
        afterGroup { dockerizedKafka.close() }

        lateinit var topic: String
        beforeEach { topic = "topic-${UUID.randomUUID()}" }

        val clientProducer: () -> KafkaClient = { KafkaClient(dockerizedKafka.bootstrapServers, TopicPartition(topic, 0)) }
        globalLogClientTestBattery(clientProducer)

        syncTestBattery("mvstore", 2, { createMvstoreUMN() }, clientProducer, 2.seconds)

        writerReaderTestBattery("mvstore", 2, { createMvstoreUMN() }, clientProducer)
    }
})
