package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.LogRecord
import com.gitlab.mvysny.umn.core.UMN
import java.io.Closeable

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
