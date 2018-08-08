package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.*
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.ConnectException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
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
 * @param globalLogClient not closed by this class
 */
internal class GlobalLogPusher(
    private val globalLogClient: GlobalLogClient,
    private val buffer: FIFOBuffer<SerializedLogRecord>
) : Closeable {
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
 *
 * Adding syncing aspect to already existing UMN database works, but in that case this function blocks until the
 * `changes` buffer is built.
 *
 * Warning: never use more than one sync on an UMN storage since they will use the same buffer
 * and will replicate different pieces of data!
 * @param client the client, must not be started yet. Closed when the UMN is closed.
 * @receiver wrap this UMN
 * @return a wrapped receiver which syncs all changes to the global log.
 */
private fun UMN.syncingOld(client: GlobalLogClient, serializer: DocumentSerializer): UMN {
    // buffer which stores outgoing changes until they are correctly transmitted over given
    // client to the global log
    val log = MutationLog(this, "OUTGOING_BUFFER", serializer)

    GlobalLogPuller(this, client)
    // also starts the client in the background
    val pusher = GlobalLogPusher(client, log.log)

    try {
        val announcingUMN = AnnouncingUMN(this, log)
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
 * Wraps given receiver and syncs all changes to the global log through given [client].
 *
 * Adding syncing aspect to already existing UMN database works, but in that case this function blocks until the
 * `changes` buffer is built.
 *
 * Warning: never use more than one sync on an UMN storage since they will use the same buffer
 * and will replicate different pieces of data!
 *
 * You need to call `SyncingUMN.syncer.start()` to actually start syncing.
 *
 * @param clientProvider creates new clients. The client produced must not be started yet. Closed when the [SyncingUMN] is closed or when
 * the [Syncer] is [Syncer.stop]ped.
 * @receiver wrap this UMN
 * @param autoStart if true the syncer is started automatically. Defaults to false.
 * @return a wrapped receiver which syncs all changes to the global log.
 */
fun UMN.syncing(clientProvider: () -> GlobalLogClient, serializer: DocumentSerializer, autoStart: Boolean = false): SyncingUMN {
    val syncingUMN = Syncer(this, serializer, clientProvider).syncingUMN
    if (autoStart) {
        syncingUMN.syncer.start()
    }
    return syncingUMN
}

/**
 * Properly closes [pusher], [client] and [delegate] when [close]d.
 */
internal class SyncCloserUMN(override val delegate: UMN, private val client: GlobalLogClient,
                             private val pusher: GlobalLogPusher
) : UMN by delegate {
    override fun close() {
        pusher.closeQuietly()
        client.closeQuietly()
        delegate.close()
    }
}

/**
 * Syncs all documents as they are modified in this UMN. To start the synchronization, call [Syncer.start].
 */
class SyncingUMN internal constructor(override val delegate: AnnouncingUMN, val syncer: Syncer) : UMN by delegate {
    override fun close() {
        syncer.close()
        delegate.close()
    }
}

class Syncer(private val underlyingUMN: UMN, serializer: DocumentSerializer, private val clientProvider: ()->GlobalLogClient) : Closeable {
    /*
     * buffer which stores outgoing changes until they are correctly transmitted over given
      */
    private val mutationLog = MutationLog(underlyingUMN, "OUTGOING_BUFFER", serializer)

    private val eventLoop = Executors.newSingleThreadExecutor()
    //guarded-by: run in eventLoop
    private var client: GlobalLogClient? = null
    private var pusher: GlobalLogPusher? = null

    private val announcingUMN = AnnouncingUMN(underlyingUMN, mutationLog)
    /**
     * Use this UMN to interact with the database. All actions done on this UMN will be synced (once the syncing is [start]ed).
     */
    val syncingUMN = SyncingUMN(announcingUMN, this)

    fun start() = event {
        if (client == null) {
            client = clientProvider()
            GlobalLogPuller(underlyingUMN, client!!)
            // also starts the client in the background
            pusher = GlobalLogPusher(client!!, mutationLog.log)
        }
    }

    private fun event(block: ()->Unit) {
        eventLoop.submit {
            try {
                if (!eventLoop.isShutdown) {
                    block()
                }
            } catch (t: Throwable) {
                log.error("Syncer failed", t)
            }
        }
    }

    fun stop() = event {
        pusher?.closeQuietly()
        pusher = null
        client?.closeQuietly()
        client = null
    }

    override fun close() {
        try {
            stop()
        } catch (e: RejectedExecutionException) {
            // already shut down, do nothing
        }
        eventLoop.shutdown()
    }

    companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(Syncer::class.java)
    }
}
