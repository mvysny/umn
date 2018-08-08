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
    override fun create(document: Any, id: UUID) = delegate.create(document, id)

    override fun create(document: Any): UUID {
        val id = delegate.create(document)
        listener(LogRecord.DocumentCreated(document, typeToClassResolver.toType(document.javaClass.kotlin), id))
        return id
    }

    override fun <V : Any> find(id: UUID, clazz: KClass<V>): V? = delegate.find(id, clazz)

    override fun exists(id: UUID): Boolean = delegate.exists(id)

    override fun update(id: UUID, document: Any) {
        delegate.update(id, document)
        listener(LogRecord.DocumentUpdated(document, typeToClassResolver.toType(document.javaClass.kotlin), id))
    }

    override fun delete(id: UUID) {
        delegate.delete(id)
        listener(LogRecord.DocumentDeleted(id))
    }

    override fun <K : Comparable<K>, V> openUtilityMap(name: String): UtilityMap<K, V> = delegate.openUtilityMap(name)

    override fun close() {
        delegate.close()
    }

    override fun findSerialized(id: UUID): SerializedDocument? = delegate.findSerialized(id)

    override val typeToClassResolver: TypeToClassResolver
        get() = delegate.typeToClassResolver

    override fun getSequenceOfIDs(): Sequence<UUID> = delegate.getSequenceOfIDs()
}

sealed class LogRecord(open val id: UUID) {
    abstract fun writeTo(dout: DataOutputStream, serializer: DocumentSerializer)
    fun toByteArray(serializer: DocumentSerializer): ByteArray = ByteArrayOutputStream().apply { writeTo(DataOutputStream(this), serializer) } .toByteArray()
    abstract fun applyTo(umn: UMN)

    data class DocumentCreated(val document: Any, val type: Short, override val id: UUID) : LogRecord(id) {
        override fun applyTo(umn: UMN) {
            umn.create(document, id)
        }

        override fun writeTo(dout: DataOutputStream, serializer: DocumentSerializer) {
            dout.writeByte(TYPE_DOCUMENT_CREATED)
            dout.writeLong(id.leastSignificantBits)
            dout.writeLong(id.mostSignificantBits)
            dout.writeShort(type.toInt())
            // @todo write payload size so that we can analyze log items server-side without reading the actual document
            // this will allow us to purge the server-side log by removing of the deleted documents
            serializer.serialize(document, dout)
        }

        companion object {
            /**
             * @param din must be buffered or backed by a [java.io.ByteArrayInputStream]!
             */
            internal fun readFrom(id: UUID, din: DataInputStream, serializer: DocumentSerializer, typeToClassResolver: TypeToClassResolver): DocumentCreated {
                val type = din.readShort()
                val clazz = typeToClassResolver.toClass(type)
                val doc = serializer.deserialize(clazz, din)
                return DocumentCreated(doc, type, id)
            }
        }
    }

    /**
     * Optimizes initial log population of the SyncingUMN.
     * @property serializedDocumentWithType serialized document
     */
    class DocumentCreated2(val serializedDocumentWithType: SerializedDocument, override val id: UUID) : LogRecord(id) {
        override fun applyTo(umn: UMN) {
            throw IllegalStateException("Should not be called")
        }

        override fun writeTo(dout: DataOutputStream, serializer: DocumentSerializer) {
            dout.writeByte(TYPE_DOCUMENT_CREATED)
            dout.writeLong(id.leastSignificantBits)
            dout.writeLong(id.mostSignificantBits)
            // @todo write payload size so that we can analyze log items server-side without reading the actual document
            // this will allow us to purge the server-side log by removing of the deleted documents
            dout.write(serializedDocumentWithType)
        }
    }

    data class DocumentDeleted(override val id: UUID) : LogRecord(id) {
        override fun applyTo(umn: UMN) {
            umn.delete(id)
        }

        override fun writeTo(dout: DataOutputStream, serializer: DocumentSerializer) {
            dout.writeByte(TYPE_DOCUMENT_DELETED)
            dout.writeLong(id.leastSignificantBits)
            dout.writeLong(id.mostSignificantBits)
        }

        companion object {
            internal fun readFrom(id: UUID) = DocumentDeleted(id)
        }
    }

    data class DocumentUpdated(val document: Any, val type: Short, override val id: UUID) : LogRecord(id) {
        override fun applyTo(umn: UMN) {
            if (umn.exists(id)) {
                umn.update(id, document)
            }
        }

        override fun writeTo(dout: DataOutputStream, serializer: DocumentSerializer) {
            dout.writeByte(TYPE_DOCUMENT_UPDATED)
            dout.writeLong(id.leastSignificantBits)
            dout.writeLong(id.mostSignificantBits)
            dout.writeShort(type.toInt())
            serializer.serialize(document, dout)
        }

        companion object {
            /**
             * @param din must be buffered or backed by a [java.io.ByteArrayInputStream]!
             */
            internal fun readFrom(id: UUID, din: DataInputStream, serializer: DocumentSerializer, typeToClassResolver: TypeToClassResolver): DocumentUpdated {
                val type = din.readShort()
                val clazz = typeToClassResolver.toClass(type)
                val doc = serializer.deserialize(clazz, din)
                return DocumentUpdated(doc, type, id)
            }
        }
    }

    companion object {
        private val TYPE_DOCUMENT_CREATED = 0
        private val TYPE_DOCUMENT_DELETED = 1
        private val TYPE_DOCUMENT_UPDATED = 2

        /**
         * @param din must be buffered or backed by [java.io.ByteArrayInputStream].
         */
        private fun readFrom(din: DataInputStream, serializer: DocumentSerializer,
                             typeToClassResolver: TypeToClassResolver): LogRecord {
            val type = din.readByte().toInt()
            val lsb = din.readLong()
            val msb = din.readLong()
            val id = UUID(msb, lsb)
            return when (type) {
                TYPE_DOCUMENT_CREATED -> DocumentCreated.readFrom(id, din, serializer, typeToClassResolver)
                TYPE_DOCUMENT_DELETED -> DocumentDeleted.readFrom(id)
                TYPE_DOCUMENT_UPDATED -> DocumentUpdated.readFrom(id, din, serializer, typeToClassResolver)
                else -> throw RuntimeException("Unsupported document type: $type")
            }
        }

        fun fromByteArray(bytes: ByteArray, serializer: DocumentSerializer,
                          typeToClassResolver: TypeToClassResolver): LogRecord =
                readFrom(DataInputStream(bytes.inputStream()), serializer, typeToClassResolver)
    }
}
