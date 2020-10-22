package com.gitlab.mvysny.umn.sync

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Use [InMemoryGlobalLog.newClient] to construct new clients.
 *
 * Should be thread-safe :-D
 */
public class InMemoryGlobalLogClient internal constructor(public val owner: InMemoryGlobalLog) : GlobalLogClient {
    override val isStarted: Boolean
        get() = started.get() && !closed.get()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    internal var addCallback: () -> Unit = {}
    internal var receiveCallback: (record: SerializedLogRecord?) -> Unit = {}
    private var currentLogPointer: Long = 0L

    private fun checkNotClosed() {
        check(!closed.get()) { "Already closed" }
    }

    private fun checkNotStarted() {
        check(!started.get()) { "Already started" }
    }

    override fun start() {
        checkNotClosed()
        check(started.compareAndSet(false, true)) { "Already started" }
        val logItems = owner.startClient(this, currentLogPointer)
        for (logItem in logItems) {
            onNewItem(logItem)
        }
    }

    override fun setAddCallback(callback: () -> Unit) {
        checkNotStarted()
        addCallback = callback
    }

    override fun setReceiverCallback(rewind: Long, callback: (record: SerializedLogRecord?) -> Unit) {
        require(rewind >= 0L) { "Parameter rewind: invalid value $rewind - must be 0 or greater" }
        checkNotStarted()
        currentLogPointer = rewind
        receiveCallback = callback
    }

    override fun addAsync(record: SerializedLogRecord) {
        check(started.get()) { "Not yet started" }
        checkNotClosed()
        owner.add(this, record)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            owner.close(this)
        }
    }

    @Synchronized
    internal fun onNewItem(record: SerializedLogRecord) {
        receiveCallback(record)
        currentLogPointer++
    }
}

/**
 * Should be thread-safe :-D Check [log] for all messages sent by any of clients produced by [newClient].
 */
public class InMemoryGlobalLog() {
    private val clients = CopyOnWriteArrayList<InMemoryGlobalLogClient>()
    /**
     * Any message sent by any client produced by [newClient] is stored here.
     */
    internal val log = CopyOnWriteArrayList<SerializedLogRecord>()

    public fun newClient() : GlobalLogClient = InMemoryGlobalLogClient(this)

    @Synchronized
    internal fun startClient(client: InMemoryGlobalLogClient, retrieveLogFrom: Long): List<SerializedLogRecord> {
        clients.add(client)
        return log.subList(retrieveLogFrom.toInt(), log.size)
    }

    internal fun close(client: InMemoryGlobalLogClient) {
        clients.remove(client)
    }

    @Synchronized
    internal fun add(client: InMemoryGlobalLogClient, record: SerializedLogRecord) {
        log.add(record)
        client.addCallback()
        clients.forEach { it.onNewItem(record) }
    }
}
