package com.gitlab.mvysny.umn.sync.kafka

import com.gitlab.mvysny.umn.core.JavaDocumentSerializer
import com.gitlab.mvysny.umn.core.LogRecord
import org.apache.kafka.common.TopicPartition
import java.util.*

fun main(args: Array<String>) {
    // first run Kafka:
    // docker run --rm -d -p 2181:2181 -p 9092:9092 --env ADVERTISED_HOST="127.0.0.1" spotify/kafka

    KafkaClient("127.0.0.1:9092", TopicPartition("test", 0)).use { client ->
        client.setAddCallback { println("SENT") }
        client.setReceiverCallback(0L) { log->
            println("RECEIVED: $log")
        }
        client.start()
        client.addAsync(LogRecord.DocumentCreated("foo", 0, UUID.randomUUID()).toByteArray(JavaDocumentSerializer))
        Thread.sleep(1000)
    }
}