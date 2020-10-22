package com.gitlab.mvysny.umn.sync.mongodb

import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.SerializedLogRecord
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.CreateCollectionOptions
import org.bson.Document
import org.bson.types.Binary
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Synchronizes UMN over a Mongo DB collection.
 * @property clientSettings the Mongo DB client configuration
 * @property databaseName the MongoDB database to use
 * @property collectionName the collection which will hold the log. Auto-created if it doesn't exist. It must be a capped collection!
 * @property pollDelayMs active polling of new records in the collection: how long to wait before next check. 10 seconds is probably a good value.
 * @property cappedCollectionSizeInBytes how many records will the log hold. Defaults to 1TB but you may use even larger numbers.
 * @author mavi
 */
class SyncMongoDBClient(
    val clientSettings: MongoClientSettings,
    val databaseName: String,
    val collectionName: String,
    val pollDelayMs: Long,
    val cappedCollectionSizeInBytes: Long = 1024L * 1024L * 1024L * 1024L
) : GlobalLogClient {

    override val isStarted: Boolean
        get() = started.get() && !closed.get()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private var addCallback: () -> Unit = {}
    private var receiveCallback: (record: SerializedLogRecord?) -> Unit = { _ -> }
    private var offset: Long = 0
    private val itemAddedQueue = LinkedBlockingQueue<SerializedLogRecord>()
    private val itemAddedLock = ReentrantLock()
    private var client: MongoClient? = null
    private var collection: MongoCollection<Document>? = null

    private fun checkNotClosed() = check(!closed.get()) { "Already closed" }
    private fun checkNotStarted() = check(!started.get()) { "Already started" }

    private var watcher: ActivePollingMongoCollectionWatcher? = null

    private fun MongoDatabase.findCollectionInfo(name: String): Document? {
        val collections = listCollections().toList()
        return collections.firstOrNull { it["name"] == name }
    }

    @Synchronized
    override fun start() {
        checkNotClosed()
        checkNotStarted()
        val client: MongoClient = MongoClients.create(clientSettings)
        val db = client.getDatabase(databaseName)
        val c = db.findCollectionInfo("mycollection")
        if (c != null) {
            // check that it is capped
            check((c["options"] as? Document)?.get("capped") as? Boolean == true) {
                "There already is collection mycollection but it is not capped! I need capped collection which guarantees iteration order based on insertion order: $c"
            }
        } else {
            db.createCollection(
                "mycollection",
                CreateCollectionOptions().capped(true).sizeInBytes(cappedCollectionSizeInBytes)
            )
        }
        collection = db.getCollection(collectionName)
        this.client = client
        started.set(true)

        watcher = ActivePollingMongoCollectionWatcher({ _, log ->
            // even if itemAdded() is invoked in parallel, the queue should help keeping
            // the total order
            itemAddedQueue.add(log)
            itemAddedLock.withLock {
                val ba = itemAddedQueue.remove()
                receiveCallback(ba)
            }
        }, collection!!, offset, pollDelayMs)
    }

    override fun setReceiverCallback(rewind: Long, callback: (record: SerializedLogRecord?) -> Unit) {
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

    override fun addAsync(record: SerializedLogRecord) {
        check(started.get()) { "Not started" }
        collection!!.insertOne(Document("l", Binary(record)))
        addCallback()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        watcher?.close()
        client?.close()
    }
}

/**
 * Change streams are only available on replica: https://stackoverflow.com/questions/48139224/mongodb-change-stream-replica-set-limitation
 *
 * Therefore, either you need to have a complex setup, or a non-recommended setup of one-node replica, or we can't use change streams.
 * Instead of forcing the user to use complex setup, we'll use active polling.
 * @property listener notified of incoming log records
 * @property collection listen on new items on this collection
 * @property offset seek before listening (skip first n documents)
 * @property delayMs active polling of new log records from the server: how long to wait before next check.
 */
private class ActivePollingMongoCollectionWatcher(private val listener: (current: Int, SerializedLogRecord)->Unit, val collection: MongoCollection<Document>,
                                                  private var offset: Long,
                                                  val delayMs: Long) : Closeable {
    private val closing = AtomicBoolean(false)
    private val t: Thread
    init {
        t = thread(isDaemon = true, name = ActivePollingMongoCollectionWatcher::class.java.simpleName) {
            try {
                var current = offset.toInt()
                while(!closing.get()) {
                    collection.find().skip(current).iterator().use { iterator ->
                        while (iterator.hasNext() && !closing.get()) {
                            val doc = iterator.next()
                            val binary = doc["l"] as Binary
                            listener(current, binary.data)
                            current++
                        }
                    }
                    if (!closing.get()) {
                        Thread.sleep(delayMs)
                    }
                }
            } catch (t: Throwable) {
                if (t is InterruptedException && closing.get()) {
                    // this is fine
                } else {
                    log.error("Watcher thread failed: $t", t)
                }
            }
        }
    }

    override fun close() {
        if (closing.compareAndSet(false, true)) {
            t.interrupt()
            t.join(1000)
        }
    }

    companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(ActivePollingMongoCollectionWatcher::class.java)
    }
}
