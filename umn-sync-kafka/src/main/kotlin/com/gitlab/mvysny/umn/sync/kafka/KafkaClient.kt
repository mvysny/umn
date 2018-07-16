package com.gitlab.mvysny.umn.sync.kafka

import com.gitlab.mvysny.umn.core.DocumentSerializer
import com.gitlab.mvysny.umn.core.LogRecord
import com.gitlab.mvysny.umn.core.tryInit
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.lang.Exception
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private var addCallback: () -> Unit = {}
    private var receiveCallback: (record: LogRecord) -> Unit = {}
    private var offset: Long = 0L
    /**
     * The consumer thread polling [consumer]. Started in [start], killed in [close].
     */
    @Volatile
    private var consumerThread: KafkaConsumerThread? = null

    private fun checkNotClosed() = check(!closed.get()) { "Already closed" }

    private fun checkNotStarted() = check(!started.get()) { "Already started" }

    override fun setReceiverCallback(rewind: Long, callback: (record: LogRecord) -> Unit) {
        require(rewind >= 0) { "rewind: $rewind must be 0 or greater" }
        checkNotClosed()
        checkNotStarted()
        offset = rewind
        receiveCallback = callback
    }

    override fun setAddCallback(callback: () -> Unit) {
        checkNotClosed()
        checkNotStarted()
        addCallback = callback
    }

    override fun start() {
        checkNotClosed()
        if (!started.compareAndSet(false, true)) {
            throw IllegalStateException("Already started")
        }

        val config = mapOf("bootstrap.servers" to bootstrapServers,
            // I have no idea what group.id means, but apparently KafkaProducer needs it for something... or was it KafkaConsumer?
            "group.id" to "unused",
            // this is very good to have, unfortunately we need newer Kafka for this
            // @todo mavi uncomment when there is newer Kafka
//            "enable.idempotence" to true,
            // Kafka spuriously can't sync metadata ... whatever. Decrease for faster sync
            ProducerConfig.MAX_BLOCK_MS_CONFIG to 1000L)
        consumerThread = KafkaConsumerThread(config,
            offset,
            topicPartition,
            serializer,
            receiveCallback,
            addCallback).apply { start() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (!started.get()) return
        consumerThread!!.close()
    }

    override fun addAsync(record: LogRecord) {
        checkNotClosed()
        check(started.get()) { "Not started" }
        consumerThread!!.addAsync(record)
    }
}

private class KafkaConsumerThread(config: Map<String, Any>,
                                  val offset: Long,
                                  val topicPartition: TopicPartition,
                                  val serializer: DocumentSerializer,
                                  val receiveCallback: (record: LogRecord)->Unit,
                                  val addCallback: () -> Unit) :
    Thread("Kafka Client Consumer Thread"), Closeable {
    /**
     * The Kafka producer, initialized in [start].
     *
     * Closed in [close].
     */
    private val producer: KafkaProducer<String, ByteArray>
    /**
     * The Kafka consumer, initialized in [start]. Polled in its own thread, hence the usage
     * is thread-safe as mandated by the [KafkaConsumer] API.
     *
     * Closed in [close].
     */
    private lateinit var consumer: KafkaConsumer<String, ByteArray>

    private val closed = AtomicBoolean()

    init {
        require(offset >= 0L)
        isDaemon = true
        producer = KafkaProducer<String, ByteArray>(config, StringSerializer(), ByteArraySerializer()).tryInit {
            consumer = KafkaConsumer<String, ByteArray>(config, StringDeserializer(), ByteArrayDeserializer())
        }
        // retarded KafkaProducer will deadlock if we immediately tried to send a record.
        // https://issues.apache.org/jira/browse/KAFKA-7085
        Thread.sleep(2000)
    }

    override fun run() {
        try {
            consumer.assign(listOf(topicPartition))
            consumer.seek(topicPartition, offset)
            while (!closed.get()) {
                val records: ConsumerRecords<String, ByteArray> = consumer.poll(1000L)
                records.forEach { record ->
                    val rec = LogRecord.fromByteArray(record.value(), serializer)
                    try {
                        receiveCallback(rec)
                    } catch (t: Throwable) {
                        log.error("Receiver callback failed", t)
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

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        consumer.wakeup()
        join()
        consumer.close(50, TimeUnit.MILLISECONDS)
        producer.close(1, TimeUnit.SECONDS)
    }

    companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(KafkaConsumerThread::class.java)
    }

    fun addAsync(record: LogRecord) {
        checkNotClosed()
        val producerRecord: ProducerRecord<String?, ByteArray> =
            ProducerRecord(topicPartition.topic(), topicPartition.partition(), null, record.toByteArray(serializer))
        producer.send(producerRecord) { _, exception: Exception? ->
            if (exception != null) {
                if (!closed.get() && exception is TimeoutException) {
                    // Kafka Client spuriously fails with
                    // org.apache.kafka.common.errors.TimeoutException: Failed to update metadata after 60000 ms.
                    // https://issues.apache.org/jira/browse/KAFKA-7085
                    // retry? no need - we enabled the idempotence? That requires Kafka 0.11 though...

                    // This exception is also thrown when the Kafka broker is unavailable...
                    // If we don't retry, then the message is lost.
                    log.warn("Got timeout, retrying", exception)
                    addAsync(record)
                } else {
                    // @todo what to do with the exception? rethrow it?
                    log.error("Failed to send a message", exception)
                    throw RuntimeException(exception)
                }
            } else {
                addCallback()
            }
        }
    }

    private fun checkNotClosed() = check(!closed.get()) { "Already closed" }
}
