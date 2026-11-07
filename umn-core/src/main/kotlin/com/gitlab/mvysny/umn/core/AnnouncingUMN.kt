package com.gitlab.mvysny.umn.core

import org.h2.mvstore.type.DataType
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.*
import kotlin.reflect.KClass

/**
 * Announces all mutate operations made to this UMN (such as create document, modify document and delete document) to given [listener].
 * The operation takes effect even if the listener fails.
 *
 * Thread-safe.
 * @property listener notified of mutate operations.
 */
public class AnnouncingUMN(override val delegate: UMN, public val listener: Listener) : UMN {
    /**
     * Listens on log events (on all mutation operations). May be called from multiple threads at once.
     */
    public interface Listener {
        /**
         * Invoked for all mutation operations performed on the UMN. The UMN operation blocks until the listener is done;
         * it's therefore important that the listener runs quickly.
         *
         * The operation has already been performed. If listener fails, the exception is propagated outwards, but that won't
         * rollback the operation itself.
         * @param logRecord the record containing all info about the mutation. Only the following record types are reported:
         * [LogRecord.DocumentCreated], [LogRecord.DocumentUpdated], [LogRecord.DocumentDeleted].
         */
        public fun onRecord(logRecord: LogRecord)
    }

    override fun create(document: Any, id: UUID): Boolean = delegate.create(document, id)

    override fun create(document: Any): UUID {
        val id = delegate.create(document)
        listener.onRecord(LogRecord.DocumentCreated(document, typeToClassResolver.toType(document.javaClass.kotlin), id))
        return id
    }

    override fun <V : Any> find(id: UUID, clazz: KClass<V>): V? = delegate.find(id, clazz)

    override fun exists(id: UUID): Boolean = delegate.exists(id)

    override fun update(id: UUID, document: Any) {
        delegate.update(id, document)
        listener.onRecord(LogRecord.DocumentUpdated(document, typeToClassResolver.toType(document.javaClass.kotlin), id))
    }

    override fun delete(id: UUID) {
        delegate.delete(id)
        listener.onRecord(LogRecord.DocumentDeleted(id))
    }

    override fun <K : Comparable<K>, V> openUtilityMap(name: String, keyType: DataType?, valueType: DataType?): UtilityMap<K, V> = delegate.openUtilityMap(name, keyType, valueType)

    override fun close() {
        delegate.close()
    }

    override fun findSerialized(id: UUID): SerializedDocument? = delegate.findSerialized(id)

    override val typeToClassResolver: TypeToClassResolver
        get() = delegate.typeToClassResolver

    override fun getSequenceOfIDs(): Sequence<UUID> = delegate.getSequenceOfIDs()
}

/**
 * The class itself is not meant to be [Serializable] since that is unpredictable, slow and tends to differ between JVMs.
 * Instead, use [LogRecord.toByteArray] and [LogRecord.fromByteArray].
 * @property id the ID of the document being created, updated or deleted.
 */
public sealed class LogRecord(public open val id: UUID) {
    public abstract fun writeTo(dout: DataOutputStream, serializer: DocumentSerializer)
    /**
     * Serializes this log record into a byte array.
     */
    public fun toByteArray(serializer: DocumentSerializer): ByteArray = serializeData { writeTo(this, serializer) }
    /**
     * Applies this record to the [umn]: it either creates a ducment with given ID, updates it or deletes it, based on the
     * type of this record.
     */
    public abstract fun applyTo(umn: UMN)

    /**
     * A [document] with given [type] has been created.
     */
    public data class DocumentCreated(val document: Any, val type: DocType, override val id: UUID) : LogRecord(id) {
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

        public companion object {
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
    public class DocumentCreated2(public val serializedDocumentWithType: SerializedDocument, override val id: UUID) : LogRecord(id) {
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

    /**
     * A document with given [id] has been deleted.
     */
    public data class DocumentDeleted(override val id: UUID) : LogRecord(id) {
        override fun applyTo(umn: UMN) {
            umn.delete(id)
        }

        override fun writeTo(dout: DataOutputStream, serializer: DocumentSerializer) {
            dout.writeByte(TYPE_DOCUMENT_DELETED)
            dout.writeLong(id.leastSignificantBits)
            dout.writeLong(id.mostSignificantBits)
        }

        public companion object {
            internal fun readFrom(id: UUID) = DocumentDeleted(id)
        }
    }

    /**
     * A document with [id] and given [type] has been updated to [document].
     */
    public data class DocumentUpdated(val document: Any, val type: DocType, override val id: UUID) : LogRecord(id) {
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

        public companion object {
            /**
             * @param din must be buffered or backed by a [java.io.ByteArrayInputStream]!
             */
            internal fun readFrom(id: UUID, din: DataInputStream, serializer: DocumentSerializer, typeToClassResolver: TypeToClassResolver): DocumentUpdated {
                val type: DocType = din.readShort()
                val clazz = typeToClassResolver.toClass(type)
                val doc = serializer.deserialize(clazz, din)
                return DocumentUpdated(doc, type, id)
            }
        }
    }

    public companion object {
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

        public fun fromByteArray(bytes: ByteArray?, serializer: DocumentSerializer,
                          typeToClassResolver: TypeToClassResolver): LogRecord? =
                if (bytes == null) null else readFrom(DataInputStream(bytes.inputStream()), serializer, typeToClassResolver)
    }
}
