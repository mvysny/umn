package com.gitlab.mvysny.umn.sync.kafka

import com.github.mvysny.dynatest.DynaTest
import com.gitlab.mvysny.umn.core.JavaDocumentSerializer
import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.sync.tester.Container
import com.gitlab.mvysny.umn.sync.tester.Docker
import com.gitlab.mvysny.umn.sync.tester.globalLogClientTestBattery
import org.apache.kafka.common.TopicPartition
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs Kafka in Docker. To connect to this Kafka, just connect to
 * `127.0.0.1:9092`.
 */
fun Docker.startKafka() = Container("""docker run --rm -d -p 2181:2181 -p 9092:9092 --env ADVERTISED_HOST="127.0.0.1" spotify/kafka""")

class KafkaClientTest : DynaTest({
    if (Docker.isPresent) {
        // we will only start Kafka once and make every test use a new topic.
        // tests will run much faster this way than if we restarted Kafka after every test
        lateinit var dockerizedKafka: Container
        beforeGroup { dockerizedKafka = Docker.startKafka(); Thread.sleep(10000) }
        afterGroup { dockerizedKafka.closeQuietly() }

        val topicGenerator = AtomicInteger()
        lateinit var topic: String
        beforeEach { topic = "topic-${topicGenerator.getAndIncrement()}" }

        globalLogClientTestBattery {
            KafkaClient("127.0.0.1:9092", TopicPartition(topic, 0), JavaDocumentSerializer)
        }
    }
})
