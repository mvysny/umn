package com.gitlab.mvysny.umn.sync.mongodb

import com.gitlab.mvysny.umn.core.DocumentSerializer
import com.gitlab.mvysny.umn.core.LogRecord
import com.gitlab.mvysny.umn.core.TypeToClassResolver
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import java.net.ConnectException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Synchronizes UMN over a Mongo DB collection.
 * @property clientSettings the Mongo DB client configuration
 * @property database the MongoDB database to use
 * @property collection the collection which will hold the log. IT MUST BE
 * @author mavi
 */
class SyncMongoDBClient(
    val clientSettings: MongoClientSettings,
    val database: String,
    val collection: String,
    val serializer: DocumentSerializer,
    val typeToClassResolver: TypeToClassResolver
) : GlobalLogClient {

    override val isStarted: Boolean
        get() = started.get() && !closed.get()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private var addCallback: () -> Unit = {}
    private var receiveCallback: (record: LogRecord) -> Unit = { _ -> }
    private var offset: Int = 0
    private val itemAddedQueue = LinkedBlockingQueue<ByteArray>()
    private val itemAddedLock = ReentrantLock()
    private var client: MongoClient? = null

    private fun checkNotClosed() = check(!closed.get()) { "Already closed" }
    private fun checkNotStarted() = check(!started.get()) { "Already started" }

    @Synchronized
    override fun start() {
        checkNotClosed()
        checkNotStarted()
        val client: MongoClient = MongoClients.create(clientSettings)

        todo check collection

        this.client = client
        started.set(true)

        val list = client.getList<ByteArray>(topic)
        list.addItemListener(object : ItemListener<ByteArray>{
            override fun itemRemoved(item: ItemEvent<ByteArray>) {
                throw RuntimeException("Can not happen - the items are only appended to the log")
            }

            override fun itemAdded(item: ItemEvent<ByteArray>) {
                // even if itemAdded() is invoked in parallel, the queue should help keeping
                // the total order
                itemAddedQueue.add(item.item)
                itemAddedLock.withLock {
                    val ba = itemAddedQueue.remove()
                    val record = LogRecord.fromByteArray(ba, serializer, typeToClassResolver)
                    receiveCallback(record)
                }
            }
        }, true)

        itemAddedLock.withLock {
            // list.size sa moze kedykolvek zmenit nam pod zadkom, pretoze niekto prida item
            // ale to by uz malo ist cez listener.
            // preto my len prejdeme list a zavolame callback pre kazdy item.
            val size = list.size
            list.subList(offset.coerceAtMost(size), size).forEach { ba ->
                val record = LogRecord.fromByteArray(ba, serializer, typeToClassResolver)
                receiveCallback(record)
            }
        }
    }

    override fun setReceiverCallback(rewind: Long, callback: (record: LogRecord) -> Unit) {
        require(rewind >= 0) { "rewind: $rewind must be 0 or greater" }
        checkNotClosed()
        checkNotStarted()
        offset = rewind.toInt()
        receiveCallback = callback
    }

    override fun setAddCallback(callback: () -> Unit) {
        checkNotClosed()
        checkNotStarted()
        addCallback = callback
    }

    override fun addAsync(record: SerializedLogRecord) {
        check(started.get()) { "Not started" }
        client!!.getList<ByteArray>(topic).add(record)
        addCallback()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        client?.close()
    }

}