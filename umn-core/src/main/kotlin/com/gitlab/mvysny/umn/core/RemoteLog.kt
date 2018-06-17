package com.gitlab.mvysny.umn.core

import java.io.Closeable
import kotlin.concurrent.thread

/**
 * Connects multitude of [UMN]s and synchronizes data. Implementations of this interface
 * include for example Kafka client.
 *
 * This interface represents a *remote log* which is essentially a list of
 * [LogItem]s with 0-based indices, which maintains a total order of the items.
 * Items are added at the end of the log. The item ordering is important and must never be changed.
 * Newly added items must be announced to the receiver callback in exactly the same order
 * as they were announced. The remote log maintains the proper
 * ordering and is the single source of truth.
 *
 * This interface represents a connected client; connection details depend on a concrete
 * client implementation and are implementation-dependent. The client initially does nothing
 * until [setReceiverCallback] is called; then at first it announces all items with proper indices.
 * Then newly added items are simply announced as they are added.
 *
 * The client must reconnect automatically on non-fatal IO exceptions such as connection
 * failures. todo how to announce fatal exceptions such as username/password mismatch?
 *
 * When the client is closed, it must not fire any callbacks anymore.
 */
interface RemoteLog : Closeable {

    /**
     * Sets the receiver [callback]. The callback is invoked for every item with index equal to
     * or greater than given [rewind] parameter, for every new [LogItem] available.
     * The log items must be announced in the same order as they were introduced; the
     * `orderNumber` parameter in the [callback] must increase as the callback is called.
     *
     * The internal item index is increased and next log item is retrieved to the listener
     * regardless of whether the listener succeeded or failed.
     *
     * The [callback] must be called by at most one thread at once.
     */
    fun setReceiverCallback(rewind: Long, callback: (item: LogItem, orderNumber: Long) -> Unit)

    /**
     * Invoked for every [LogItem] sent by [addAsync]. The `orderNumber` in the callback
     * is the global index number assigned to the [LogItem] by the server. The `orderNumber` must
     * increase as the callback is called. @todo what if the callback fails?
     *
     * The [callback] must be called by at most one thread at once.
     */
    fun setAddCallback(callback: (orderNumber: Long) -> Unit)

    /**
     * Appends given [item] into the global log asynchronously. Returns immediately.
     * When the [item] has been added successfully server-side, the [setAddCallback] callback
     * is called.
     */
    fun addAsync(item: LogItem)
}

/**
 * Asynchronously pushes contents of the [buffer] to the [remoteLog]. Only removes the item from the buffer
 * when the [RemoteLog.addAsync] has been ACKed; that means that double-sending may occur on
 * failures or when this class is closed while the item is being sent. This is however okay
 * since [LogItem]s are idempotent (may be executed multiple times without side effects).
 *
 * Typically used in conjunction with [AnnouncingUMN] which announces all mutation ops which are
 * then pushed by this class to the global log.
 */
internal class RemoteLogPusher(private val remoteLog: RemoteLog, private val buffer: FIFOBuffer<LogItem>) : Closeable {
    init {
        remoteLog.setAddCallback {
            // when the remote log ACKs that the item has been added, remove it from the buffer
            buffer.poll()
        }
    }

    private var closed: Boolean = false

    private val pusherThread = thread(isDaemon = true, name = "UMN Remote Log Pusher") {
        while (!closed) {
            val item = buffer.peek()
            if (item == null) {
                Thread.sleep(100)
            } else {
                remoteLog.addAsync(item)
            }
        }
    }

    fun addAsync(item: LogItem) {
        buffer.append(item)
    }

    override fun close() {
        closed = true
        pusherThread.join()
    }
}

/**
 * Polls given [remoteLog] for new records and stores them into the [umn].
 *
 * WARNING: make sure to NOT to pass the [AnnouncingUMN] in as the value of [umn],
 * since replaying of the ops will trigger the [AnnouncingUMN.listener] which will
 * in turn publish the change into the log, which will in turn notify this class,
 * which will in turn replay the op which will trigger the [AnnouncingUMN.listener] ... etc.
 *
 * Note: items already applied in this UMN will too be published via [RemoteLogPusher],
 * pulled via this class and applied back to the UMN. This is fine since
 * [LogItem]s are idempotent (may be executed multiple times without side effects).
 * Also the items will be applied in correct global order hence everybody will see the
 * same effect.
 */
internal class RemoteLogPuller(val umn: UMN, private val remoteLog: RemoteLog) {
    private val currentLogPointer: UtilityMap<Boolean, Long> = umn.openUtilityMap("CURRENT_LOG_PTR")
    init {
        val currentPtr = currentLogPointer.find(false) ?: 0L
        remoteLog.setReceiverCallback(currentPtr) { item, newPtr ->
            item.applyTo(umn)
            currentLogPointer.put(false, newPtr)
        }
    }
}
