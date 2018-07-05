package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.*
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.ConnectException
import kotlin.concurrent.thread

/**
 * Connects multitude of [UMN]s to a *global log* and synchronizes data. For example,
 * one implementation would be a Kafka client, connecting to a Kafka server which holds the
 * global log itself, as a topic with one partition.
 *
 * ### Global Log
 *
 * The *global log* is essentially a list of
 * [LogRecord]s with 0-based indices, which maintains a total order of the items.
 * Items are added at the end of the log only, never into the middle of the log.
 * The item ordering is important and must never be changed.
 * Newly added items must be announced to the receiver callback in exactly the same order
 * as they were announced. The remote log maintains the proper
 * ordering and is the single source of truth.
 *
 * ### Optional Global Log optimizations
 *
 * The log may contain holes. Say a UMN document has been created and later on, deleted.
 * It is for example no longer necessary to synchronize all modifications to the document;
 * only the [LogRecord.DocumentDeleted] needs to be transmitted. Therefore we can safely
 * remove al [LogRecord.DocumentCreated] and [LogRecord.DocumentUpdated] related to this document from
 * the log, thus saving both the disk space and the network bandwidth.
 *
 * This is an optional optimization - some global log implementations may decide to not to
 * optimize and hold all [LogRecord]s indefinitely.
 *
 * ### Client
 *
 * This interface represents a (connected) client; connection details depend on a concrete
 * client implementation and are implementation-dependent. The client initially does nothing;
 * the following need to be done first:
 * * The [setReceiverCallback] must be called, to set the callback invoked for every matching item.
 *   The items are announced in exactly the same order in which they are stored in the global log
 * * The [setAddCallback] must be called, which ACKs that the log item has been sent successfully
 *   and is now appended in the global log
 * * Then, call [start] to connect to the global log and start firing callbacks.
 * * Only now the [addAsync] can be called.
 * * At some point, when [close] is called, the client disconnects and no callbacks are made. This
 *   is the final state - a closed client can not be reused nor started.
 *
 * The client must reconnect automatically on non-fatal IO exceptions such as connection
 * failures. todo how to announce fatal exceptions such as username/password mismatch?
 *
 * When the client is closed, it must not fire any callbacks anymore.
 */
interface GlobalLogClient : Closeable {

    /**
     * Sets the receiver [callback]. The callback is invoked for every item with index equal to
     * or greater than given [rewind] parameter, for every new [LogRecord] available.
     * The log items must be announced in the same order as they were introduced; the
     * `orderNumber` parameter in the [callback] must increase as the callback is called.
     *
     * The internal item index is increased and next log item is retrieved to the listener
     * regardless of whether the listener succeeded or failed.
     *
     * The [callback] must be called by at most one thread at once.
     * @param rewind start announcing new messages from the log with this message. Must be 0 or
     * greater.
     * @throws IllegalStateException if the client has already been started or is closed
     */
    fun setReceiverCallback(rewind: Long, callback: (record: LogRecord) -> Unit)

    /**
     * Invoked for every [LogRecord] sent by [addAsync] of this client, serves as a confirmation
     * that the item has been stored in the global log. @todo what if the callback fails?
     *
     * The [callback] is called for any [LogRecord] including items published via this client.
     *
     * The [callback] must be called by at most one thread at once.
     * @throws IllegalStateException if the client has already been started or is closed
     */
    fun setAddCallback(callback: () -> Unit)

    /**
     * Starts the client - connects to the global log (if it hasn't been connected already)
     * and starts firing events. The method may be synchronous and may start firing events
     * already from the method body.
     *
     * The client can be started only once, and from one thread in parallel only.
     *
     * The method may block (potentially endlessly) until a successful connection has been made. Alternatively, the method
     * may throw [java.io.IOException] - in this case start should be re-attempted again after a small backoff period.
     *
     * If [close] is called, the client must stop starting/connecting and return from this method (normal return or throw an exception).
     * @throws IllegalStateException if the client is already started or has been closed.
     * @throws java.io.IOException on recoverable I/O exception
     * @throws java.net.ConnectException a special subclass of IOException when we know that the server is down.
     */
    fun start()

    /**
     * Appends given [record] into the global log asynchronously. Does not wait while the
     * record is added to the log.
     *
     * When the [record] has been added successfully server-side, the [setAddCallback] callback
     * is called. Afterwards, the record is also announced to all clients via the [setReceiverCallback].
     *
     * The method can block if the internal outgoing buffer is full, or if the server is currently
     * unavailable. The internal outgoing buffer should be quite small since [GlobalLogPusher]
     * stores outgoing messages on-disk first.
     *
     * Only called by one thread.
     *
     * @throws IllegalStateException if the client is not yet started or has been closed.
     */
    fun addAsync(record: LogRecord)

    /**
     * Returns true after [start] has been called. Returns false if the client is closed.
     */
    val isStarted: Boolean
}

/**
 * Asynchronously pushes contents of the [buffer] to the [globalLogClient]. Only removes the item from the buffer
 * when the [GlobalLogClient.addAsync] has been ACKed; that means that double-sending may occur on
 * failures or when this class is closed while the item is being sent. This is however okay
 * since [LogRecord]s are idempotent (may be executed multiple times without side effects).
 * todo what if one client sends 'update' and dies, another updates to something else, and then
 * the first client re-sends the first 'update'?
 *
 * Typically used in conjunction with [AnnouncingUMN] which announces all mutation ops which are
 * then pushed by this class to the global log.
 *
 * @param globalLogClient not closed by this class
 */
internal class GlobalLogPusher(private val globalLogClient: GlobalLogClient, private val buffer: FIFOBuffer<LogRecord>) : Closeable {
    init {
        globalLogClient.setAddCallback {
            // when the remote log ACKs that the item has been added, remove it from the buffer
            buffer.poll()
        }
    }

    private var closed: Boolean = false

    private val pusherThread = thread(isDaemon = true, name = "UMN Remote Log Pusher") {

        fun tryStartClient() {
            try {
                globalLogClient.start()
                Thread.sleep(100)
            } catch (e: ConnectException) {
                log.info("Failed to connect to the server", e)
                Thread.sleep(1000)
            }
        }

        try {
            while (!closed) {
                try {
                    if (!globalLogClient.isStarted) {
                        tryStartClient()
                    } else {
                        val item = buffer.peek()
                        if (item == null) {
                            Thread.sleep(100)
                        } else {
                            globalLogClient.addAsync(item)
                        }
                    }
                } catch (t: Throwable) {
                    log.error("Pusher Thread failure", t)
                    Thread.sleep(5000)
                }
            }
        } catch (t: Throwable) {
            log.error("Pusher Thread fatal failure", t)
        }
    }

    fun addAsync(record: LogRecord) {
        buffer.append(record)
    }

    override fun close() {
        closed = true
        pusherThread.join()
    }

    companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(GlobalLogPusher::class.java)
    }
}

/**
 * Polls given [globalLogClient] for new records and stores them into the [umn].
 *
 * WARNING: make sure to NOT to pass the [AnnouncingUMN] in as the value of [umn],
 * since replaying of the ops will trigger the [AnnouncingUMN.listener] which will
 * in turn publish the change into the log, which will in turn notify this class,
 * which will in turn replay the op which will trigger the [AnnouncingUMN.listener] ... etc.
 *
 * Note: items already applied in this UMN will too be published via [GlobalLogPusher],
 * pulled via this class and applied back to the UMN. This is fine since
 * [LogRecord]s are idempotent (may be executed multiple times without side effects).
 * Also the items will be applied in correct global order hence everybody will see the
 * same effect.
 *
 * @param globalLogClient not closed by this class
 */
internal class GlobalLogPuller(val umn: UMN, private val globalLogClient: GlobalLogClient) {
    private val currentLogPointer: UtilityMap<Boolean, Long> = umn.openUtilityMap("CURRENT_LOG_PTR")
    init {
        var currentPtr = currentLogPointer.find(false) ?: 0L
        globalLogClient.setReceiverCallback(currentPtr) { item ->
            item.applyTo(umn)
            currentLogPointer.put(false, ++currentPtr)
        }
    }
}

/**
 * Wraps given receiver and syncs all changes to the global log through given [client].
 * To correctly propagate changes you must use the [UMN] returned by this function, otherwise
 * nothing will be synced.
 *
 * Warning: never use more than one sync on an UMN storage since they will use the same buffer
 * and will replicate different pieces of data!
 * @param client the client, must not be started yet. Closed when the UMN is closed.
 * @receiver wrap this UMN
 * @return a wrapped receiver which syncs all changes to the global log.
 */
fun UMN.syncing(client: GlobalLogClient): UMN {
    // buffer which stores outgoing changes until they are correctly transmitted over given
    // client to the global log
    val changes = FIFOBuffer<LogRecord>(this.openUtilityMap("OUTGOING_BUFFER"))

    GlobalLogPuller(this, client)
    // also starts the client in the background
    val pusher = GlobalLogPusher(client, changes)

    try {
        val announcingUMN = AnnouncingUMN(this) { item -> pusher.addAsync(item) }
        val replicatingUMN = SyncCloserUMN(announcingUMN, client, pusher)
        return replicatingUMN
    } catch (t: Throwable) {
        // if anything fails, clean up
        pusher.closeQuietly()
        client.closeQuietly()
        throw t
    }
}

/**
 * Properly closes [pusher], [client] and [delegate] when [close]d.
 */
internal class SyncCloserUMN(private val delegate: UMN, private val client: GlobalLogClient,
                             private val pusher: GlobalLogPusher
) : UMN by delegate {
    override fun close() {
        pusher.closeQuietly()
        client.closeQuietly()
        delegate.close()
    }
}
