package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.*
import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.reflect.KClass

/**
 * A [LogRecord]'s bytes, from [LogRecord.toByteArray]; plain bytes, no Java serialization.
 */
@Deprecated("Use ByteArray: the alias hid that a record is plain bytes", ReplaceWith("ByteArray"))
public typealias SerializedLogRecord = ByteArray

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
 * @param forceRepopulate if true, the [fifolog] will be destroyed and repopulated from the database. Use this when moving
 * sync to another sync server.
 */
public class MutationLog(
        public val umn: UMN,
        bufferName: String,
        public val serializer: DocumentSerializer,
        public val forceRepopulate: Boolean
) : AnnouncingUMN.Listener {
    public val fifolog: AckFIFOBuffer<ByteArray>

    init {
        // buffer which stores the UMN mutation log.
        val map: UtilityMap<Long, ByteArray> = umn.openUtilityMap(bufferName)
        val initialPopulationMarker = umn.openUtilityMap<String, Boolean>("$bufferName-marker")
        fifolog = AckFIFOBuffer(map)
        if (forceRepopulate || initialPopulationMarker.find("isPopulated") == null) {
            fifolog.clear()

            // this UMN has not been synced yet. We need to "fake" the history by creating DocumentCreated
            // records for all existing documents.
            initialPopulationMarker.put("isPopulated", true)
            log.info("log history missing in $bufferName, creating fake from all current documents")
            check(map.isEmpty()) { "The FIFOBuffer was expected to be empty but it contains keys ${map.keyIterator(Long.MIN_VALUE..Long.MAX_VALUE).toList()}!!!" }
            var count = 0
            umn.getSequenceOfSerializedDocs().forEach {
                // the serializer is not going to be called by DocumentCreated2, we can pass in a dummy impl
                fifolog.append(LogRecord.DocumentCreated2(it.second, it.first).toByteArray(FailingSerializer))
                count++
            }
            log.info("log history faked, FIFOBuffer should now have $count records")
        }
    }

    override fun onRecord(logRecord: LogRecord) {
        fifolog.append(logRecord.toByteArray(serializer))
    }

    /**
     * Empties this log. Warning! This operation is NOT atomic - if multiple threads call this, items are randomly drained to both
     * threads.
     */
    public fun drain(): List<LogRecord> =
        generateSequence { val item = fifolog.poll(); fifolog.ackLowest(); item }
            .map { LogRecord.fromByteArray(it, serializer, umn.typeToClassResolver)!! }
            .toList()

    public companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(MutationLog::class.java)
    }
}
