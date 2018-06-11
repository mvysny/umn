package com.gitlab.mvysny.umn.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.*
import kotlin.reflect.KClass

/**
 * Stores a log of all operations into an internal utility table.
 *
 * Thread-unsafe.
 *
 * @todo implement support for log replaying
 */
class ReplayLogUMN(private val delegate: UMN, private val serializer: DocumentSerializer) : UMN {
    private val log = delegate.openUtilityMap<Long, ByteArray>("REPLAY_LOG")
    override fun <V : Any> find(id: UUID, clazz: KClass<V>): V? = delegate.find(id, clazz)

    override fun exists(id: UUID): Boolean = delegate.exists(id)

    override fun <K : Comparable<K>, V> openUtilityMap(name: String): UtilityMap<K, V> = delegate.openUtilityMap(name)

    override fun close() {
        delegate.close()
    }

    override fun create(document: Any): UUID {
        val id = delegate.create(document)
        appendLog(LogItem.DocumentCreated(document, id))
        return id
    }

    private fun appendLog(logItem: LogItem) {
        val id: Long = (log.getLargestKey() ?: 0L) + 1L
        log.put(id, logItem.toByteArray(serializer))
    }

    override fun delete(id: UUID, clazz: KClass<out Any>) {
        delegate.delete(id, clazz)
        appendLog(LogItem.DocumentDeleted(id))
    }

    override fun <V : Any> update(id: UUID, document: V, clazz: KClass<V>) {
        delegate.update(id, document, clazz)
        appendLog(LogItem.DocumentUpdated(document, id))
    }
}

sealed class LogItem(val id: UUID) {
    abstract fun writeTo(dout: DataOutputStream, serializer: DocumentSerializer)
    fun toByteArray(serializer: DocumentSerializer): ByteArray = ByteArrayOutputStream().apply { writeTo(DataOutputStream(this), serializer) } .toByteArray()

    class DocumentCreated(val document: Any, id: UUID) : LogItem(id) {
        override fun writeTo(dout: DataOutputStream, serializer: DocumentSerializer) {
            dout.writeByte(TYPE_DOCUMENT_CREATED)
            dout.writeLong(id.leastSignificantBits)
            dout.writeLong(id.mostSignificantBits)
            dout.writeUTF(document.javaClass.name)
            // @todo write payload size so that we can analyze log items server-side without reading the actual document
            // this will allow us to purge the server-side log by removing of the deleted documents
            serializer.serialize(document, dout)
        }

        companion object {
            internal fun readFrom(id: UUID, din: DataInputStream, serializer: DocumentSerializer): DocumentCreated {
                val clazz = Class.forName(din.readUTF()).asSubclass(Any::class.java)
                val doc = serializer.deserialize(clazz.kotlin, din)
                return DocumentCreated(doc, id)
            }
        }
    }

    class DocumentDeleted(id: UUID) : LogItem(id) {
        override fun writeTo(dout: DataOutputStream, serializer: DocumentSerializer) {
            dout.writeByte(TYPE_DOCUMENT_DELETED)
            dout.writeLong(id.leastSignificantBits)
            dout.writeLong(id.mostSignificantBits)
        }
    }

    class DocumentUpdated(val document: Any, id: UUID) : LogItem(id) {
        override fun writeTo(dout: DataOutputStream, serializer: DocumentSerializer) {
            dout.writeByte(TYPE_DOCUMENT_UPDATED)
            dout.writeLong(id.leastSignificantBits)
            dout.writeLong(id.mostSignificantBits)
            dout.writeUTF(document.javaClass.name)
            serializer.serialize(document, dout)
        }

        companion object {
            internal fun readFrom(id: UUID, din: DataInputStream, serializer: DocumentSerializer): DocumentUpdated {
                val clazz = Class.forName(din.readUTF()).asSubclass(Any::class.java)
                val doc = serializer.deserialize(clazz.kotlin, din)
                return DocumentUpdated(doc, id)
            }
        }
    }

    companion object {
        private val TYPE_DOCUMENT_CREATED = 0
        private val TYPE_DOCUMENT_DELETED = 1
        private val TYPE_DOCUMENT_UPDATED = 2

        fun readFrom(din: DataInputStream, serializer: DocumentSerializer): LogItem {
            val type = din.readByte().toInt()
            val lsb = din.readLong()
            val msb = din.readLong()
            val id = UUID(msb, lsb)
            return when (type) {
                TYPE_DOCUMENT_CREATED -> DocumentCreated.readFrom(id, din, serializer)
                TYPE_DOCUMENT_DELETED -> DocumentDeleted(id)
                TYPE_DOCUMENT_UPDATED -> DocumentUpdated.readFrom(id, din, serializer)
                else -> throw RuntimeException("Unsupported document type: $type")
            }
        }
    }
}
