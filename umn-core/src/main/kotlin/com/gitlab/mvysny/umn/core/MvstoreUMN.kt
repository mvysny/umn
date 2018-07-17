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
     */
    fun serialize(document: Any, dout: DataOutputStream)

    /**
     * Deserializes the object of given [clazz] from given [din] input stream.
     * We need to know the class upfront: it is needed both by the JSON Serializer, and possibly by custom serializers.
     */
    fun <T: Any> deserialize(clazz: KClass<T>, din: DataInputStream): T

    fun serialize(document: Any): ByteArray = ByteArrayOutputStream().apply {
        serialize(document, DataOutputStream(this))
    }.toByteArray()

    fun <T: Any> deserialize(clazz: KClass<T>, bytes: ByteArray): T = deserialize(clazz, DataInputStream(bytes.inputStream()))
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
 */
class MvstoreUMN(private val store: MVStore, private val serializer: DocumentSerializer) : UMN {
    override val delegate: UMN?
        get() = null

    override fun create(document: Any, id: UUID): Boolean {
        return map.putIfAbsent(id, serializer.serialize(document)) == null
    }

    override fun close() {
        store.close()
    }

    private val map: MVMap<UUID, ByteArray> = store.openMap("ROOT")

    override fun create(document: Any): UUID {
        val id = UUID.randomUUID()
        map[id] = serializer.serialize(document)
        return id
    }

    override fun <V : Any> find(id: UUID, clazz: KClass<V>): V? {
        val bytes = map[id]
        return if (bytes == null) null else serializer.deserialize(clazz, bytes)
    }

    override fun exists(id: UUID): Boolean = map.containsKey(id)

    override fun <V: Any> update(id: UUID, document: V, clazz: KClass<V>) {
        map.computeIfPresent(id, { _, _ -> serializer.serialize(document) }) ?: throw IllegalArgumentException("Document with $id does not exist" )
    }

    override fun delete(id: UUID, clazz: KClass<out Any>) {
        map.remove(id)
    }

    override fun <K : Comparable<K>, V> openUtilityMap(name: String): UtilityMap<K, V> = MvstoreUtilityMap(store, name)
}

/**
 * Thread-safe.
 */
class MvstoreUtilityMap<K: Comparable<K>, V>(store: MVStore, name: String) : UtilityMap<K, V> {
    override fun keyIterator(range: ClosedRange<K>): Iterator<K> {
        if (range.isEmpty()) return emptySequence<K>().iterator()
        val keySequence = map.keyIterator(range.start).asSequence()
        return keySequence.takeWhile { key -> key in range } .iterator()
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
}
