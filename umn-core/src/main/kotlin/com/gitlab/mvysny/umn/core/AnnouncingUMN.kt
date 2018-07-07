package com.gitlab.mvysny.umn.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.*
import kotlin.reflect.KClass

/**
 * Announces all mutate operations made to this UMN (such as create document, modify document and delete document) to given [listener].
 * The operation takes effect even if the listener fails.
 *
 * Thread-safe.
 *
 * @property listener notified of mutate operations. May be called from multiple threads at once.
 */
class AnnouncingUMN(override val delegate: UMN, private val listener: (LogRecord)->Unit = {}) : UMN {
    override fun create(document: Any, id: UUID) {
        delegate.create(document, id)
    }

    override fun create(document: Any): UUID {
        val id = delegate.create(document)
        listener(LogRecord.DocumentCreated(document, id))
        return id
    }

    override fun <V : Any> find(id: UUID, clazz: KClass<V>): V? = delegate.find(id, clazz)

    override fun exists(id: UUID): Boolean = delegate.exists(id)

    override fun <V : Any> update(id: UUID, document: V, clazz: KClass<V>) {
        delegate.update(id, document, clazz)
        listener(LogRecord.DocumentUpdated(document, id))
    }

    override fun delete(id: UUID, clazz: KClass<out Any>) {
        delegate.delete(id, clazz)
        listener(LogRecord.DocumentDeleted(id, clazz))
    }

    override fun <K : Comparable<K>, V> openUtilityMap(name: String): UtilityMap<K, V> = delegate.openUtilityMap(name)

    override fun close() {
        delegate.close()
    }
}

sealed class LogRecord(open val id: UUID) {
    abstract fun writeTo(dout: DataOutputStream, serializer: DocumentSerializer)
    fun toByteArray(serializer: DocumentSerializer): ByteArray = ByteArrayOutputStream().apply { writeTo(DataOutputStream(this), serializer) } .toByteArray()
    abstract fun applyTo(umn: UMN)

    data class DocumentCreated(val document: Any, override val id: UUID) : LogRecord(id) {
        override fun applyTo(umn: UMN) {
            umn.create(document, id)
        }

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

    data class DocumentDeleted(override val id: UUID, val clazz: KClass<*>) : LogRecord(id) {
        override fun applyTo(umn: UMN) {
            umn.delete(id, clazz)
        }

        override fun writeTo(dout: DataOutputStream, serializer: DocumentSerializer) {
            dout.writeByte(TYPE_DOCUMENT_DELETED)
            dout.writeLong(id.leastSignificantBits)
            dout.writeLong(id.mostSignificantBits)
            dout.writeUTF(clazz.java.name)
        }

        companion object {
            internal fun readFrom(id: UUID, din: DataInputStream): DocumentDeleted {
                val clazz = Class.forName(din.readUTF()).asSubclass(Any::class.java)
                return DocumentDeleted(id, clazz.kotlin)
            }
        }
    }

    data class DocumentUpdated(val document: Any, override val id: UUID) : LogRecord(id) {
        override fun applyTo(umn: UMN) {
            if (umn.exists(id)) {
                umn.update(id, document, document.javaClass.kotlin)
            }
        }

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

        fun readFrom(din: DataInputStream, serializer: DocumentSerializer): LogRecord {
            val type = din.readByte().toInt()
            val lsb = din.readLong()
            val msb = din.readLong()
            val id = UUID(msb, lsb)
            return when (type) {
                TYPE_DOCUMENT_CREATED -> DocumentCreated.readFrom(id, din, serializer)
                TYPE_DOCUMENT_DELETED -> DocumentDeleted.readFrom(id, din)
                TYPE_DOCUMENT_UPDATED -> DocumentUpdated.readFrom(id, din, serializer)
                else -> throw RuntimeException("Unsupported document type: $type")
            }
        }

        fun fromByteArray(bytes: ByteArray, serializer: DocumentSerializer): LogRecord =
                readFrom(DataInputStream(bytes.inputStream()), serializer)
    }
}
