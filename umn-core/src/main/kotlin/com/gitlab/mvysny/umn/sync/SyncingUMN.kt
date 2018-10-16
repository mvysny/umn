package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.*
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.ConnectException
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
 * @param globalLogClient not closed by this class
 */
private class GlobalLogPusher(
    private val globalLogClient: GlobalLogClient,
    private val buffer: AckFIFOBuffer<SerializedLogRecord>
) : Closeable {
    init {
        globalLogClient.setAddCallback {
            // when the remote log ACKs that the item has been added, remove it from the buffer
            buffer.ackLowest()
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
                        val item = buffer.poll()
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
private class GlobalLogPuller(val umn: UMN, private val globalLogClient: GlobalLogClient,
                              val documentSerializer: DocumentSerializer,
                              val typeToClassResolver: TypeToClassResolver,
                              forceRestartSync: Boolean) {
    private val currentLogPointer: UtilityMap<Boolean, Long> = umn.openUtilityMap("CURRENT_LOG_PTR")
    init {
        if (forceRestartSync) {
            // start from the beginning, pull all changes from the server.
            currentLogPointer.put(false, null)
        }

        // continue pulling data where we left off
        var currentPtr = currentLogPointer.find(false) ?: 0L
        globalLogClient.setReceiverCallback(currentPtr) { serializedItem ->
            val item = LogRecord.fromByteArray(serializedItem, documentSerializer, typeToClassResolver)
            item.applyTo(umn)
            currentLogPointer.put(false, ++currentPtr)
        }
    }
}

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
 * @param clientProvider creates new clients. The client produced must not be started yet. Closed when the [SyncingUMN] is closed or when
 * the [Syncer] is [Syncer.stop]ped.
 * @param serializer serializes/deserializes documents
 * @receiver wrap this UMN
 * @param autoStart if true the syncer is started automatically. Defaults to false.
 * @param forceRestartSync DANGEROUS. If true, all sync history is thrown out on this client, a fake sync history is reconstructed from the database
 * and everything is pushed to the global log. Only use this if you are switching your clients to a new sync server.
 * @return a wrapped receiver which syncs all changes to the global log.
 */
fun UMN.syncing(clientProvider: () -> GlobalLogClient, serializer: DocumentSerializer, autoStart: Boolean = false, forceRestartSync: Boolean = false): SyncingUMN {
    val syncingUMN = Syncer(this, serializer, clientProvider, forceRestartSync).syncingUMN
    if (autoStart) {
        syncingUMN.syncer.start()
    }
    return syncingUMN
}

/**
 * Syncs all documents as they are modified in this UMN. To start the synchronization, call [Syncer.start].
 */
class SyncingUMN internal constructor(override val delegate: AnnouncingUMN, val syncer: Syncer) : UMN by delegate {
    override fun close() {
        syncer.close()
        syncer.awaitClose()
        delegate.close()
    }
}

/**
 * Thread-safe syncer syncing given UMN. Use [UMN.syncing] to obtain instances of this class. Then, call [start] to start syncing.
 *
 * *Warning*: never use more than one sync on an UMN storage since they will use the same buffer in a thread-unsafe way
 * and they will send same changes multiple times to other nodes!
 */
class Syncer internal constructor(private val underlyingUMN: UMN,
                                  serializer: DocumentSerializer,
                                  private val clientProvider: ()->GlobalLogClient,
                                  forceRestartSync: Boolean) : Closeable {
    /*
     * buffer which stores outgoing changes until they are correctly transmitted over given
      */
    private val mutationLog = MutationLog(underlyingUMN, "OUTGOING_BUFFER", serializer, forceRestartSync)

    private val eventLoop = Executors.newSingleThreadExecutor()
    //guarded-by: run in eventLoop
    private var client: GlobalLogClient? = null
    //guarded-by: run in eventLoop
    private var pusher: GlobalLogPusher? = null

    private val announcingUMN = AnnouncingUMN(underlyingUMN, mutationLog)
    /**
     * Use this UMN to interact with the database. All actions done on this UMN will be stored in the mutation log
     * and synced (once the syncing is [start]ed).
     */
    val syncingUMN = SyncingUMN(announcingUMN, this)

    private var forceRestartSyncOnFirstStart = forceRestartSync

    /**
     * Starts syncing. Thread-safe. Attempt to start an already started syncer does nothing.
     */
    fun start() = event {
        if (client == null) {
            client = clientProvider()
            GlobalLogPuller(underlyingUMN, client!!, mutationLog.serializer, underlyingUMN.typeToClassResolver, forceRestartSyncOnFirstStart)
            forceRestartSyncOnFirstStart = false
            // also starts the client in the background
            pusher = GlobalLogPusher(client!!, mutationLog.fifolog)
        }
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
    fun stop() = event(true) {
        // tu musim dat forceRun=true, inak close() by nefungoval:
        // lebo close() zavola stop() a hned shutdowne eventLoop, co je race condition, lebo ked je stop kod konecne zavolany,
        // zisti ze eventLoop je shutdownuty a nic neurobi.
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

    fun awaitClose() {
        eventLoop.awaitTermination(1, TimeUnit.SECONDS)
    }

    companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(Syncer::class.java)
    }
}
