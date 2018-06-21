package com.gitlab.mvysny.umn.sync.kafka

import com.gitlab.mvysny.umn.core.JavaDocumentSerializer
import com.gitlab.mvysny.umn.core.LogRecord
import org.apache.kafka.common.TopicPartition
import java.util.*

fun main(args: Array<String>) {
    // first run Kafka:
    // docker run --rm -d -p 2181:2181 -p 9092:9092 --env ADVERTISED_HOST="127.0.0.1" spotify/kafka

    KafkaClient("127.0.0.1:9092", TopicPartition("test", 0),
        JavaDocumentSerializer).use { client ->
        client.setAddCallback { println("SENT: $it") }
        client.setReceiverCallback(0L) { log, offset ->
            println("RECEIVED: $offset - $log")
        }
        client.start()
        client.addAsync(LogRecord.DocumentCreated("foo", UUID.randomUUID()))
        Thread.sleep(1000)
    }
}