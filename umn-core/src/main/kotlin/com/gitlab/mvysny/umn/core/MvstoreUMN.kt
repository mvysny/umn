package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVMap
import org.h2.mvstore.MVStore
import java.io.*
import java.util.*
import kotlin.reflect.KClass

/**
 * Provides support for serializing/deserializing of objects. You can either use:
 * * The standard Java Serialization (by the means of [JavaDocumentSerializer])
 * * You can store the documents as JSON (say by using `Gson`)
 * * You can provide a completely custom serialization solution
 */
interface DocumentSerializer {
    /**
     * Serializes the [document] into the [dout] stream. Don't close the stream - it is automatically closed afterwards.
     * @param document the document to write to the stream.
     * @param dout write the document data here. Don't close. The stream is buffered or backed by a [ByteArrayOutputStream] so there is no
     * need to buffer it.
     */
    fun serialize(document: Any, dout: DataOutputStream)

    /**
     * Deserializes the object of given [clazz] from given [din] input stream.
     * We need to know the class upfront: it is needed both by the JSON Serializer, and possibly by custom serializers.
     * @param din read the document data from here. No need to close. The stream is buffered or backed by a [ByteArrayInputStream] so
     * there is no need to buffer it.
     */
    fun <T: Any> deserialize(clazz: KClass<T>, din: DataInputStream): T

    fun serialize(document: Any): ByteArray = ByteArrayOutputStream().apply {
        serialize(document, DataOutputStream(this))
    }.toByteArray()

    fun <T: Any> deserialize(clazz: KClass<T>, bytes: ByteArray): T = deserialize(clazz, DataInputStream(bytes.inputStream()))
}

/**
 * Every document stored has a type associated. This resolver is able to provide a [KClass] for document type,
 * so that we can load and deserialize the document properly.
 */
interface TypeToClassResolver {
    fun toClass(type: Short): KClass<*>
    fun toType(clazz: KClass<*>): Short
}

/**
 * Uses standard Java Serialization to serialize/deserialize objects. Slow, should only be used for testing purposes.
 */
object JavaDocumentSerializer : DocumentSerializer {
    override fun serialize(document: Any, dout: DataOutputStream) {
        ObjectOutputStream(dout).writeObject(document)
    }
    override fun <T : Any> deserialize(clazz: KClass<T>, din: DataInputStream): T = clazz.java.cast(ObjectInputStream(din).readObject())
}

/**
 * Stores documents into H2 MVStore. Warning: [serializer] is used every time when a document is stored or retrieved!
 *
 * Thread-safe.
 *
 * Supports all [Serializable]/[Externalizable] keys; for more info please consult the documentation for H2 MV Store.
 *
 * @property store the underlying store to use, closed when this UMN is closed.
 * @param typeResolver used once, when the mvstore is migrated from v1 to v2.
 */
class MvstoreUMN(private val store: MVStore, private val serializer: DocumentSerializer,
                 override val typeToClassResolver: TypeToClassResolver,
                 val typeResolver: (UUID)->Short) : UMN {
    override val delegate: UMN?
        get() = null

    override fun create(document: Any, id: UUID): Boolean
            = map.putIfAbsent(id, serializer.serialize(document)) == null

    override fun close() {
        store.close()
    }

    private val map: MVMap<UUID, ByteArray> = store.openMap("ROOT2")

    init {
        // migrate to v2 with types
        val oldMap: MVMap<UUID, ByteArray> = store.openMap("ROOT")
        if (oldMap.isNotEmpty()) {
            map.clear()
            oldMap.keyIterator(null).forEach { id ->
                val clazz = typeToClassResolver.toClass(typeResolver(id))
                val doc = serializer.deserialize(clazz, oldMap[id]!!)
                map[id] = serialize(doc)
            }
            oldMap.clear()
        }
    }

    private fun serialize(doc: Any): ByteArray = serializeData {
        writeShort(typeToClassResolver.toType(doc.javaClass.kotlin).toInt())
        serializer.serialize(doc, this)
    }

    private fun deserialize(bytes: ByteArray): Pair<Any, Short> {
        val din = DataInputStream(ByteArrayInputStream(bytes))
        val type = din.readShort()
        return serializer.deserialize(typeToClassResolver.toClass(type), din) to type
    }

    override fun create(document: Any): UUID {
        val id = UUID.randomUUID()
        map[id] = serialize(document)
        return id
    }

    override fun <V : Any> find(id: UUID, clazz: KClass<V>): V? {
        val bytes = map[id]
        return if (bytes == null) null else clazz.java.cast(deserialize(bytes).first)
    }

    override fun exists(id: UUID): Boolean = map.containsKey(id)

    override fun update(id: UUID, document: Any) {
        map.computeIfPresent(id) { _, _ -> serialize(document) } ?: throw IllegalArgumentException("Document with $id does not exist" )
    }

    override fun delete(id: UUID) {
        map.remove(id)
    }

    override fun <K : Comparable<K>, V> openUtilityMap(name: String): UtilityMap<K, V> = MvstoreUtilityMap(store, name)

    override fun getSequenceOfIDs(): Sequence<UUID> = map.keyIterator(null).asSequence()

    override fun findSerialized(id: UUID): Pair<ByteArrayInputStream, Short>? {
        val bytes = map[id] ?: return null
        val bin = bytes.inputStream()
        val din = DataInputStream(bin)
        val type = din.readShort()
        return bin to type
    }
}

/**
 * Thread-safe.
 */
class MvstoreUtilityMap<K: Comparable<K>, V>(store: MVStore, name: String) : UtilityMap<K, V> {
    override fun keyIterator(range: ClosedRange<K>): Sequence<K> {
        if (range.isEmpty()) return emptySequence()
        val keySequence = map.keyIterator(range.start).asSequence()
        return keySequence.takeWhile { key -> key in range }
    }

    private val map: MVMap<K, V> = store.openMap("__$name")
    override fun find(key: K): V? = map[key]
    override fun put(key: K, value: V?) {
        map.putOrRemove(key, value)
    }
    override fun getSmallestKey(): K? = map.firstKey()
    override fun getLargestKey(): K? = map.lastKey()

    override fun values(): Iterator<V> = map.values.iterator()

    override fun clear() {
        map.clear()
    }

    override fun isEmpty(): Boolean = map.isEmpty()

    override fun lowerKey(key: K): K? = map.lowerKey(key)
}
