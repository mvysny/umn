package com.gitlab.mvysny.umn.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Currently thread-unsafe.
 */
class InMemoryGlobalLogClient(val owner: InMemoryGlobalLog) : GlobalLogClient {
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    internal lateinit var addCallback: (orderNumber: Long) -> Unit
    internal lateinit var receiveCallback: (item: LogItem, orderNumber: Long) -> Unit
    private var currentLogPointer: Long = 0L

    private fun checkNotClosed() {
        check(!closed.get()) { "Already closed" }
    }

    private fun checkNotStarted() {
        check(!started.get()) { "Already started" }
    }

    override fun start() {
        checkNotClosed()
        if (!started.compareAndSet(false, true)) {
            throw IllegalStateException("Already started")
        }
        val logItems = owner.startClient(this, currentLogPointer)
        for (logItem in logItems) {
            onNewItem(logItem)
        }
    }

    override fun setAddCallback(callback: (orderNumber: Long) -> Unit) {
        checkNotStarted()
        addCallback = callback
    }

    override fun setReceiverCallback(rewind: Long, callback: (item: LogItem, orderNumber: Long) -> Unit) {
        checkNotStarted()
        currentLogPointer = rewind
        receiveCallback = callback
    }

    override fun addAsync(item: LogItem) {
        check(started.get()) { "Not yet started" }
        checkNotClosed()
        owner.add(this, item)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            owner.close(this)
        }
    }

    internal fun onNewItem(item: LogItem) {
        receiveCallback(item, currentLogPointer++)
    }
}

/**
 * Currently thread-unsafe.
 */
class InMemoryGlobalLog {
    private val clients = CopyOnWriteArrayList<InMemoryGlobalLogClient>()
    internal val log = CopyOnWriteArrayList<LogItem>()

    fun newClient() : GlobalLogClient {
        val client = InMemoryGlobalLogClient(this)
        return client
    }

    internal fun startClient(client: InMemoryGlobalLogClient, retrieveLogFrom: Long): List<LogItem> {
        clients.add(client)
        return log.subList(retrieveLogFrom.toInt(), log.size)
    }

    internal fun close(client: InMemoryGlobalLogClient) {
        clients.remove(client)
    }

    internal fun add(client: InMemoryGlobalLogClient, item: LogItem) {
        log.add(item)
        val newItemIndex = (log.size - 1).toLong()
        client.addCallback(newItemIndex)
        clients.forEach { it.onNewItem(item) }
    }
}
