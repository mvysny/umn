package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.LogRecord
import com.gitlab.mvysny.umn.core.UMN
import java.io.Closeable
import java.io.IOException
import java.util.*
import java.util.concurrent.CancellationException

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
 * ### Optional Global Log Optimizations
 *
 * The log may contain holes. Say a UMN document has been created and later on, deleted.
 * It is for example no longer necessary to synchronize all modifications to the document;
 * only the [LogRecord.DocumentDeleted] needs to be transmitted. Therefore we can safely
 * remove all [LogRecord.DocumentCreated] and [LogRecord.DocumentUpdated] related to this document from
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
 * failures.
 *
 * When the client is closed, it must not fire any callbacks anymore.
 */
public interface GlobalLogClient : Closeable {
    /**
     * Sets the receiver [callback]. The callback is invoked for every item with index equal to
     * or greater than given [rewind] parameter, for every new [LogRecord] available.
     * The log items must be announced in the same order as they were introduced.
     *
     * The internal item index is increased and next log item is retrieved to the listener
     * regardless of whether the listener succeeded or failed.
     *
     * The [callback] must be called by at most one thread at once.
     * @param rewind start announcing new messages from the log with this message. Must be 0 or
     * greater.
     * @param callback invoked for every new item. Note that the record may be null in case when the Global Log
     * punched a hole into it by removing insignificant log items (e.g. updates to a record that is known to be deleted).
     * In case of null just do nothing, just increase internal sync pointer.
     * @throws IllegalStateException if the client has already been started or is closed
     */
    public fun setReceiverCallback(rewind: Long, callback: (record: SerializedLogRecord?) -> Unit)

    /**
     * Invoked for every [LogRecord] sent by [addAsync] of this client, serves as a confirmation
     * that the item has been stored in the global log. @todo what if the callback fails?
     *
     * The [callback] is called for any [LogRecord] including items published via this client.
     *
     * The [callback] must be called by at most one thread at once.
     * @throws IllegalStateException if the client has already been started or is closed
     */
    public fun setAddCallback(callback: () -> Unit)

    /**
     * Starts the client - connects to the global log (if it hasn't been connected already)
     * and starts firing events. The method may be synchronous and may start firing events
     * already from the method body.
     *
     * The client can be started only once, and from one thread only.
     *
     * The method may block (potentially endlessly) until a successful connection has been made. Alternatively, the method
     * may throw [java.io.IOException] - in this case start should be re-attempted again after a small back-off period.
     * Any other type of exception including [RuntimeException], [InvalidUsernamePasswordException] are considered fatal and the re-connection
     * must not be reattempted.
     *
     * If [close] is called, the client must stop starting/connecting and return from this method (normal return or throw [CancellationException]).
     * @throws IllegalStateException if the client is already started or has been closed.
     * @throws java.io.IOException on recoverable I/O exception
     * @throws java.net.ConnectException a special subclass of IOException when we know that the server is down.
     * @throws CancellationException if [close] has been called while we were busy in this method connecting to the server.
     * @throws InvalidUsernamePasswordException if the username/password is invalid.
     * @throws RuntimeException on other general fatal exception.
     */
    @Throws(IOException::class, InvalidUsernamePasswordException::class, CancellationException::class)
    public fun start()

    /**
     * Appends given [record] into the global log asynchronously. Does not wait while the
     * record is added to the log.
     *
     * When the [record] has been added successfully server-side, the [setAddCallback] callback
     * is called. Afterwards, the record is also announced to all clients (including this one) via the [setReceiverCallback].
     *
     * The method can block if the internal outgoing buffer is full, or if the server is currently
     * unavailable. The internal outgoing buffer should be quite small since [GlobalLogPusher]
     * stores outgoing messages on-disk first. Because of that, the internal buffer may be as small as to hold only one record.
     *
     * Only called by one thread.
     *
     * @throws IllegalStateException if the client is not yet started or has been closed.
     */
    @Throws(IOException::class)
    public fun addAsync(record: SerializedLogRecord)

    /**
     * Returns true after [start] has been called. Returns false if the client is closed.
     */
    public val isStarted: Boolean
}

public class InvalidUsernamePasswordException(message: String, cause: Throwable? = null) : Exception(message, cause)
