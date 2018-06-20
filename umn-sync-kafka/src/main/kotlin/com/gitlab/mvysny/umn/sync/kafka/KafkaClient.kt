package com.gitlab.mvysny.umn.sync.kafka

import com.gitlab.mvysny.umn.core.DocumentSerializer
import com.gitlab.mvysny.umn.core.GlobalLogClient
import com.gitlab.mvysny.umn.core.LogRecord
import com.gitlab.mvysny.umn.core.closeQuietly
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Provides means to store [LogRecord]s into given Kafka topic and partition.
 *
 * All changes are stored as a list of [LogRecord]s stored as a byte array (see
 * [LogRecord.toByteArray] for more info).
 *
 * Not compatible with Android: [KAFKA-7025](https://issues.apache.org/jira/browse/KAFKA-7025)
 *
 * @param bootstrapServers the value of Kafka `bootstrap.servers`
 * @param topicPartition the topic/partition to use.
 * @param serializer used to serialize documents stored in the [UMN] to byte array, so that
 * they can be passed wrapped in [LogRecord]s to the Kafka topic.
 */
class KafkaClient(val bootstrapServers: String, val topicPartition: TopicPartition,
                  val serializer: DocumentSerializer) : GlobalLogClient {

    override val isStarted: Boolean
        get() = started.get() && !closed.get()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private var addCallback: (orderNumber: Long) -> Unit = {}
    private var receiveCallback: (record: LogRecord, orderNumber: Long) -> Unit = { _, _ -> }
    private var offset: Long = 0L
    /**
     * The Kafka producer, initialized in [start]. @todo describe settings and why they are used.
     *
     * Closed in [close].
     */
    private var producer: KafkaProducer<String, ByteArray>? = null
    /**
     * The Kafka consumer, initialized in [start]. Polled in its own thread, hence the usage
     * is thread-safe as mandated by the [KafkaConsumer] API. @todo describe settings and why they are used.
     *
     * Closed by [consumerThread] when the thread ends.
     */
    private var consumer: KafkaConsumer<String, ByteArray>? = null
    /**
     * The consumer thread polling [consumer]. Started in [start], killed in [close].
     */
    private var consumerThread: Thread? = null

    private fun checkNotClosed() {
        check(!closed.get()) { "Already closed" }
    }

    private fun checkNotStarted() {
        check(!started.get()) { "Already started" }
    }

    override fun setReceiverCallback(rewind: Long, callback: (record: LogRecord, orderNumber: Long) -> Unit) {
        require(rewind >= 0) { "$rewind must be 0 or greater" }
        checkNotClosed()
        checkNotStarted()
        offset = rewind
        receiveCallback = callback
    }

    override fun setAddCallback(callback: (orderNumber: Long) -> Unit) {
        checkNotClosed()
        checkNotStarted()
        addCallback = callback
    }

    override fun start() {
        checkNotClosed()
        if (!started.compareAndSet(false, true)) {
            throw IllegalStateException("Already started")
        }

        // I have no idea what group.id means, but apparently KafkaProducer needs it for something... or was it KafkaConsumer?
        val config = mapOf("bootstrap.servers" to bootstrapServers, "group.id" to "unused")
        producer = KafkaProducer<String, ByteArray>(config, StringSerializer(), ByteArraySerializer())
        consumerThread = thread(isDaemon = true, name = "Kafka Client Consumer Thread") {
            try {
                consumer = KafkaConsumer<String, ByteArray>(config, StringDeserializer(), ByteArrayDeserializer())
                consumer!!.use { consumer ->
                    consumer.assign(listOf(topicPartition))
                    consumer.seek(topicPartition, offset)
                    while (true) {
                        val records = consumer.poll(1000L)
                        records.forEach { record ->
                            val rec = LogRecord.fromByteArray(record.value(), serializer)
                            try {
                                receiveCallback(rec, record.offset())
                            } catch (t: Throwable) {
                                log.error("Receiver callback failed", t)
                            }
                        }
                    }
                }
            } catch (t: WakeupException) {
                // this is normal since we're being closed...
                if (!closed.get()) throw t
            } catch (t: Throwable) {
                log.error("Kafka Client consumer thread died", t)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        consumer!!.wakeup()
        consumerThread!!.join()
        producer!!.closeQuietly()
    }

    companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(KafkaClient::class.java)
    }

    override fun addAsync(record: LogRecord) {
        checkNotClosed()
        check(started.get()) { "Not started" }
        val producerRecord: ProducerRecord<String?, ByteArray> =
            ProducerRecord(topicPartition.topic(), topicPartition.partition(), null, record.toByteArray(serializer))
        producer!!.send(producerRecord) { metadata: RecordMetadata?, exception: Exception? ->
            if (exception != null) {
                // @todo what to do with the exception? log it for now...
                log.warn("Failed to send a message", exception)
            } else {
                addCallback(metadata!!.offset())
            }
        }
    }
}