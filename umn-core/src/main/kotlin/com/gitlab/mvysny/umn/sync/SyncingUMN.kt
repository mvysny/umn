package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.*
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.IOException
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
 * Starts by re-sending every unACKed item: a previous pusher may have polled items it never
 * delivered. Once [close] returns, ACKs are ignored.
 *
 * @param globalLogClient not closed by this class
 */
private class GlobalLogPusher(
    private val globalLogClient: GlobalLogClient,
    private val buffer: AckFIFOBuffer<ByteArray>
) : Closeable {
    @Volatile
    private var closed: Boolean = false

    init {
        buffer.rewindPoll()
        globalLogClient.setAddCallback {
            // ACKs are positional: a late one from a closed client would ack an item the next
            // pusher hasn't delivered yet.
            synchronized(this) {
                if (!closed) {
                    buffer.ackLowest()
                }
            }
        }
    }

    /**
     * Pushes the buffer to the started client; returns once the client is no longer started, or [keepGoing] turns false.
     * @throws IOException when the connection fails while sending.
     */
    fun push(keepGoing: () -> Boolean) {
        while (!closed && globalLogClient.isStarted && keepGoing()) {
            val item = buffer.poll()
            if (item == null) {
                Thread.sleep(100)
            } else {
                globalLogClient.addAsync(item)
            }
        }
    }

    override fun close() {
        synchronized(this) {
            closed = true
        }
    }
}

/**
 * How long the [Syncer] waits before reconnecting: [initialMillis], doubling with every failed
 * connection in a row, up to [maxMillis].
 */
internal class Backoff(val initialMillis: Long = 1000L, val maxMillis: Long = 60_000L) {
    /**
     * @param failures failed connections in a row, 1 or more.
     */
    fun delayMillis(failures: Int): Long =
        (initialMillis shl (failures - 1).coerceIn(0, 30)).coerceAtMost(maxMillis)
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
 * Once [close] returns, records are ignored: a late one from a closed client would move the log
 * pointer under the next puller. So are the records after one that fails to apply, its [failure]:
 * the log pointer stays on it, and the next puller retries it.
 *
 * The log pointer is saved right after a commit, at most once a second ([saveIfDue]) and on [close]: a kill re-pulls
 * the records since, which apply again harmlessly, and never skips one.
 *
 * ## Implementation notes
 *
 * Saved after each record with no commit between, a store could keep the pointer and lose the records below it, as
 * MVStore stores map by map (`R_mvstore_store_per_map`). A commit per record would cost a chunk each through a whole
 * initial pull (`R_mvstore_commit_per_write`).
 *
 * @param globalLogClient not closed by this class
 */
private class GlobalLogPuller(val umn: UMN, private val globalLogClient: GlobalLogClient,
                              val documentSerializer: DocumentSerializer,
                              val typeToClassResolver: TypeToClassResolver,
                              forceRestartSync: Boolean) : Closeable {
    private val currentLogPointer: UtilityMap<Boolean, Long> = umn.openUtilityMap("CURRENT_LOG_PTR")
    private var closed: Boolean = false
    @Volatile
    var failure: LogRecordNotAppliedException? = null
        private set
    /**
     * The next record's index; [currentLogPointer] holds [savedPtr], as of [savedAtNanos].
     */
    private var currentPtr: Long
    private var savedPtr: Long
    private var savedAtNanos: Long = System.nanoTime()

    init {
        if (forceRestartSync) {
            // start from the beginning, pull all changes from the server.
            currentLogPointer.put(false, null)
        }

        // continue pulling data where we left off
        currentPtr = currentLogPointer.find(false) ?: 0L
        savedPtr = currentPtr

        globalLogClient.setReceiverCallback(currentPtr) { serializedItem ->
            synchronized(this) {
                if (!closed && failure == null) {
                    try {
                        if (serializedItem != null) {
                            val item = LogRecord.fromByteArray(serializedItem, documentSerializer, typeToClassResolver)!!
                            item.applyTo(umn)
                        }
                        currentPtr++
                        saveIfDue()
                    } catch (e: Exception) {
                        failure = LogRecordNotAppliedException(currentPtr, e)
                    }
                }
            }
        }
    }

    /**
     * Saves the log pointer if it moved and a second has passed since the last save.
     */
    fun saveIfDue() {
        synchronized(this) {
            if (!closed && currentPtr != savedPtr && System.nanoTime() - savedAtNanos >= POINTER_SAVE_INTERVAL_NANOS) {
                save()
            }
        }
    }

    private fun save() {
        umn.commit()
        currentLogPointer.put(false, currentPtr)
        savedPtr = currentPtr
        savedAtNanos = System.nanoTime()
    }

    override fun close() {
        synchronized(this) {
            if (!closed) {
                closed = true
                if (currentPtr != savedPtr) save()
            }
        }
    }
}

/**
 * How often a [GlobalLogPuller] saves its pointer: as often as MVStore's default auto-commit stores anyway.
 */
private const val POINTER_SAVE_INTERVAL_NANOS = 1_000_000_000L

/**
 * Wraps given receiver and syncs all changes to the global log through clients provided by given [clientProvider].
 * You need to call `SyncingUMN.syncer.start()` to actually start syncing, or set the [autoStart] parameter to true.
 *
 * It is possible to add syncing aspect to an already existing UMN database. If the sync log is detected to be empty,
 * a dummy [LogRecord.DocumentCreated] is created for every existing document. However, this only works once!
 * From this point on, you must use [SyncingUMN] to mutate the database, in order to properly populate the mutation log.
 * If you don't, the mutation log will not be populated with document changes and the changes will not be sent to other nodes.
 *
 * *Warning*: never use more than one sync on an UMN storage since they will use the same buffer in a thread-unsafe way
 * and they will send same changes multiple times to other nodes!
 *
 * @param clientProvider creates new clients. The client produced must not be started yet. Closed when the [SyncingUMN] is closed,
 * when the [Syncer] is [Syncer.stop]ped, or when its connection fails; the syncer then takes a new one after a back-off.
 * Both the client provider and client methods are called in a background thread.
 * @param serializer serializes/deserializes documents
 * @receiver wrap this UMN
 * @param autoStart if true the syncer is started automatically. Defaults to false.
 * @param forceRestartSync DANGEROUS. If true, all sync history is thrown out on this client, a fake sync history is reconstructed from the database
 * and everything is pushed to the global log. Only use this if you are switching your clients to a new sync server.
 * Re-pulls all events from the server - may result in duplicated records and data overwrite.
 * Always pass `false` here unless you know what you're doing!
 * @return a wrapped receiver which syncs all changes to the global log.
 */
public fun UMN.syncing(clientProvider: () -> GlobalLogClient, serializer: DocumentSerializer, autoStart: Boolean = false, forceRestartSync: Boolean = false): SyncingUMN {
    val syncingUMN = Syncer(this, serializer, clientProvider, forceRestartSync).syncingUMN
    if (autoStart) {
        syncingUMN.syncer.start()
    }
    return syncingUMN
}

/**
 * Syncs all documents as they are modified in this UMN. To start the synchronization, call [Syncer.start].
 */
public class SyncingUMN internal constructor(override val delegate: AnnouncingUMN, public val syncer: Syncer) : UMN by delegate {
    override fun close() {
        syncer.close()
        syncer.awaitClose()
        delegate.close()
    }
}

/**
 * Thread-safe syncer syncing given UMN. Use [UMN.syncing] to obtain instances of this class. Then, call [start] to start syncing.
 *
 * While started, the syncer keeps one client connected: when the client's connection fails, the syncer closes it and
 * takes a new one from [clientProvider], after a back-off growing from 1 second to 1 minute. Only an
 * [InvalidUsernamePasswordException] stops it for good; [stop] and [start] it to try again, e.g. with new credentials.
 * [setStatusListener] tells the app which of these is happening, e.g. to show "offline" or "wrong password":
 *
 * ```
 * syncingUMN.syncer.setStatusListener { status ->
 *     runOnUiThread { showSyncStatus(status) }  // Android; called on the syncer's own thread
 * }
 * ```
 *
 * *Warning*: never use more than one sync on an UMN storage since they will use the same buffer in a thread-unsafe way
 * and they will send same changes multiple times to other nodes!
 * @property clientProvider provides clients. Always called on the syncer's own background thread (which is *not* the UI thread),
 * as are the methods on [GlobalLogClient].
 * @param forceRestartSync DANGEROUS: completely destroys the sync log, rebuilds it with the current DB content and pushes it
 * to the server. Re-pulls all events from the server.
 * May result in duplicated records and data overwrite.
 * Always pass `false` here unless you know what you're doing!
 * @param backoff shortened by tests only.
 */
public class Syncer internal constructor(private val underlyingUMN: UMN,
                                  serializer: DocumentSerializer,
                                  private val clientProvider: ()->GlobalLogClient,
                                  forceRestartSync: Boolean,
                                  private val backoff: Backoff = Backoff()) : Closeable {
    /**
     * buffer which stores outgoing changes until they are correctly transmitted over [GlobalLogClient] (produced by [clientProvider]).
     */
    private val mutationLog = MutationLog(underlyingUMN, "OUTGOING_BUFFER", serializer, forceRestartSync)

    private val eventLoop = Executors.newSingleThreadExecutor()
    //guarded-by: run in eventLoop
    private var loop: SyncLoop? = null

    private val announcingUMN = AnnouncingUMN(underlyingUMN, mutationLog)
    /**
     * Use this UMN to interact with the database. All actions done on this UMN will be stored in the mutation log
     * and synced (once the syncing is [start]ed).
     */
    public val syncingUMN: SyncingUMN = SyncingUMN(announcingUMN, this)

    private var forceRestartSyncOnFirstStart = forceRestartSync

    /**
     * What the syncer is doing right now; changes on the syncer's own thread, as [setStatusListener] hears it.
     */
    @Volatile
    public var status: SyncerStatus = SyncerStatus.Stopped
        private set

    //guarded-by: run in eventLoop
    private var statusListener: SyncerStatusListener? = null

    /**
     * Starts syncing. Thread-safe. Attempt to start an already started syncer does nothing.
     */
    public fun start(): Unit = event {
        if (loop == null) {
            loop = SyncLoop(forceRestartSyncOnFirstStart)
            forceRestartSyncOnFirstStart = false
        }
    }

    /**
     * Calls [listener] with the current [status] now, then with every change, in order; null removes the
     * listener. Thread-safe; a syncer has one listener at most.
     *
     * The listener is called on the syncer's own thread, never the UI thread, and a slow one delays [start]
     * and [stop], so hop to the UI thread rather than work in it. An exception it throws is logged.
     */
    public fun setStatusListener(listener: SyncerStatusListener?): Unit = event {
        statusListener = listener
        listener?.onStatusChanged(status)
    }

    //guarded-by: run in eventLoop
    private fun publish(status: SyncerStatus) {
        this.status = status
        statusListener?.onStatusChanged(status)
    }

    private fun event(forceRun: Boolean = false, block: ()->Unit) {
        eventLoop.submit {
            try {
                if (forceRun || !eventLoop.isShutdown) {
                    block()
                }
            } catch (t: Throwable) {
                log.error("Syncer failed", t)
            }
        }
    }

    /**
     * Stops syncing. Thread-safe. Attempt to stop an already stopped syncer does nothing.
     */
    public fun stop(): Unit = event(true) {
        // tu musim dat forceRun=true, inak close() by nefungoval:
        // lebo close() zavola stop() a hned shutdowne eventLoop, co je race condition, lebo ked je stop kod konecne zavolany,
        // zisti ze eventLoop je shutdownuty a nic neurobi.
        loop?.closeQuietly()
        loop = null
        publish(SyncerStatus.Stopped)
    }

    /**
     * Keeps one client connected, on a thread of its own: a failed client is closed and replaced
     * with a new one from [clientProvider] after a [backoff].
     *
     * @param forceRestartSync passed to the first [GlobalLogPuller] only.
     */
    private inner class SyncLoop(private var forceRestartSync: Boolean) : Closeable {
        private val closed = CountDownLatch(1)
        private val isClosed: Boolean get() = closed.count == 0L
        /**
         * The current connection's pusher, puller and client, closed in this order: a late
         * callback from the client then goes nowhere. Guarded by `this`.
         */
        private var connection: List<Closeable> = listOf()
        /**
         * Failed connections in a row.
         */
        private var failures = 0
        /**
         * Connections in a row that ended on a [LogRecordNotAppliedException]. Each one connected first, resetting
         * [failures], so this is what grows the back-off while a record keeps failing.
         */
        private var stalls = 0
        private val thread = thread(isDaemon = true, name = "UMN Sync") { run() }

        private fun run() {
            while (!isClosed) {
                var cause: Throwable? = null
                var stalled = false
                try {
                    connectAndPush()
                    if (!isClosed) {
                        log.info("Lost the connection to the global log, reconnecting")
                    }
                } catch (e: InvalidUsernamePasswordException) {
                    log.error("Invalid username or password, syncing stops", e)
                    post(SyncerStatus.Failed(e))
                    return
                } catch (e: LogRecordNotAppliedException) {
                    cause = e
                    stalled = true
                    if (!isClosed) {
                        log.error("Syncing stalls on a record it can't apply, retrying it", e)
                    }
                } catch (t: Throwable) {
                    cause = t
                    if (!isClosed) {
                        log.warn("Failed to sync with the global log, reconnecting", t)
                    }
                }
                stalls = if (stalled) stalls + 1 else 0
                failures++
                val delayMillis = backoff.delayMillis(maxOf(failures, stalls))
                post(SyncerStatus.Offline(cause, delayMillis))
                closed.await(delayMillis, TimeUnit.MILLISECONDS)
            }
        }

        /**
         * Publishes [status] on the event loop, unless this loop is no longer [loop] by then.
         */
        private fun post(status: SyncerStatus) {
            try {
                event {
                    // stop() publishes Stopped after closing this loop, ahead of what it posted meanwhile
                    if (loop === this@SyncLoop) {
                        publish(status)
                    }
                }
            } catch (e: RejectedExecutionException) {
                // the syncer is closed
            }
        }

        /**
         * Returns once the connection fails or this loop closes.
         */
        private fun connectAndPush() {
            post(SyncerStatus.Connecting)
            val client = clientProvider()
            val pusher: GlobalLogPusher
            val puller: GlobalLogPuller
            synchronized(this) {
                if (isClosed) {
                    client.closeQuietly()
                    return
                }
                puller = GlobalLogPuller(underlyingUMN, client, mutationLog.serializer, underlyingUMN.typeToClassResolver, forceRestartSync)
                forceRestartSync = false
                pusher = GlobalLogPusher(client, mutationLog.fifolog)
                connection = listOf(pusher, puller, client)
            }
            try {
                client.start()
                failures = 0
                post(SyncerStatus.Connected)
                pusher.push {
                    // else, with no record coming, the pointer would stay behind the last ones until the close
                    puller.saveIfDue()
                    puller.failure == null
                }
                puller.failure?.let { throw it }
            } finally {
                closeConnection()
            }
        }

        private fun closeConnection() {
            synchronized(this) {
                connection.forEach { it.closeQuietly() }
                connection = listOf()
            }
        }

        override fun close() {
            synchronized(this) {
                closed.countDown()
                // closing the client also makes a start() still connecting return
                closeConnection()
            }
            thread.join()
        }
    }

    override fun close() {
        try {
            stop()
        } catch (e: RejectedExecutionException) {
            // already shut down, do nothing
        }
        eventLoop.shutdown()
    }

    public fun awaitClose() {
        eventLoop.awaitTermination(1, TimeUnit.SECONDS)
    }

    public companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(Syncer::class.java)
    }
}
