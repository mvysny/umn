package com.gitlab.mvysny.umn.sync.hazelcast

import com.gitlab.mvysny.umn.core.DocumentSerializer
import com.gitlab.mvysny.umn.core.LogRecord
import com.gitlab.mvysny.umn.core.TypeToClassResolver
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.ItemEvent
import com.hazelcast.core.ItemListener
import java.net.ConnectException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SyncHazelcastClient(val hostPort: String, val topic: String,
                          val serializer: DocumentSerializer, val typeToClassResolver: TypeToClassResolver) : GlobalLogClient {
    override val isStarted: Boolean
        get() = started.get() && !closed.get()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private var addCallback: () -> Unit = {}
    private var receiveCallback: (record: LogRecord) -> Unit = { _ -> }
    private var offset: Int = 0
    private val itemAddedQueue = LinkedBlockingQueue<ByteArray>()
    private val itemAddedLock = ReentrantLock()
    private var client: HazelcastInstance? = null

    private fun checkNotClosed() = check(!closed.get()) { "Already closed" }
    private fun checkNotStarted() = check(!started.get()) { "Already started" }

    @Synchronized
    override fun start() {
        checkNotClosed()
        checkNotStarted()
        val clientConfig = ClientConfig().apply {
            networkConfig.addresses.add(hostPort)
        }
        val client: HazelcastInstance = try {
            HazelcastClient.newHazelcastClient(clientConfig)
        } catch (e: IllegalStateException) {
            if (e.message!!.contains("Unable to connect to any address!")) {
                throw ConnectException(e.message).apply { initCause(e) }
            } else {
                throw e
            }
        }
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

    override fun addAsync(record: LogRecord) {
        check(started.get()) { "Not started" }
        val ba = record.toByteArray(serializer)
        client!!.getList<ByteArray>(topic).add(ba)
        addCallback()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        client?.shutdown()
    }
}
