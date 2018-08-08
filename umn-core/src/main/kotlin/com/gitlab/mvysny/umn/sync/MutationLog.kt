package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KClass

/**
 * A [LogRecord], serialized using [LogRecord.toByteArray]. Deserialize using [LogRecord.fromByteArray].
 */
typealias SerializedLogRecord = ByteArray

private object FailingSerializer : DocumentSerializer {
    override fun serialize(document: Any, dout: DataOutputStream) = throw RuntimeException("not expected to be called")
    override fun <T : Any> deserialize(clazz: KClass<T>, din: DataInputStream): T = throw RuntimeException("not expected to be called")
}

/**
 * Holds the mutation log of the UMN database, since the beginning of the database.
 * Just pass this instance to [AnnouncingUMN]'s constructor, to get it populated.
 *
 * The log is MUTABLE. By reading, the log entries are removed from the log. This is intentional: this class
 * is intended to be used by the SyncingUMN which pushes all changes to the server; pushed logs are no longer necessary
 * and may be deleted to reduce the database size.
 *
 * @param umn the underlying UMN database; just pass [AnnouncingUMN.delegate] here.
 * @param bufferName to which [UtilityMap] the mutation log will be stored.
 * @param serializer used to serialize documents to the log.
 */
class MutationLog(val umn: UMN, bufferName: String, val serializer: DocumentSerializer) : AnnouncingUMN.Listener {
    val log: FIFOBuffer<SerializedLogRecord>

    init {
        // buffer which stores the UMN mutation log.
        val map: UtilityMap<Long, SerializedLogRecord> = umn.openUtilityMap(bufferName)
        val initialPopulationMarker = umn.openUtilityMap<String, Boolean>("$bufferName-marker")
        log = FIFOBuffer(map)
        if (initialPopulationMarker.find("isPopulated") == null) {
            // this UMN has not been synced yet. We need to "fake" the history by creating DocumentCreated
            // records for all existing documents.
            initialPopulationMarker.put("isPopulated", true)
            umn.getSequenceOfSerializedDocs().forEach {
                // the serializer is not going to be called by DocumentCreated2, we can pass in a dummy impl
                log.append(LogRecord.DocumentCreated2(it.second, it.first).toByteArray(FailingSerializer))
            }
        }
    }

    override fun onRecord(logRecord: LogRecord) {
        log.append(logRecord.toByteArray(serializer))
    }

    /**
     * Empties this log. Warning! This operation is NOT atomic - if multiple threads call this, items are randomly drained to both
     * threads.
     */
    fun drain(): List<LogRecord> =
        generateSequence { log.poll() } .map { LogRecord.fromByteArray(it, serializer, umn.typeToClassResolver) } .toList()
}

/**
 * Provides a FIFO buffer abstraction while storing items into [UtilityMap].
 *
 * Thread-safe.
 */
class FIFOBuffer<T: Any>(private val log: UtilityMap<Long, T>) {
    private val readLock = ReentrantLock()
    private val writeLock = ReentrantLock()
    /**
     * Inserts the specified element at the end of this queue.
     */
    fun append(item: T) {
        writeLock.withLock {
            val id: Long = (log.getLargestKey() ?: 0L) + 1L
            log.put(id, item)
        }
    }
    /**
     * Retrieves and removes the head of this queue,
     * or returns `null` if this queue is empty.
     */
    fun poll(): T? = readLock.withLock {
        val lowestKey: Long = log.getSmallestKey() ?: return null
        val result: T? = log.find(lowestKey)
        log.put(lowestKey, null)
        result
    }

    /**
     * Retrieves (but does not remove) the head of this queue,
     * or returns `null` if this queue is empty.
     */
    fun peek(): T? = readLock.withLock {
        val lowestKey: Long = log.getSmallestKey() ?: return null
        log.find(lowestKey)
    }
}
