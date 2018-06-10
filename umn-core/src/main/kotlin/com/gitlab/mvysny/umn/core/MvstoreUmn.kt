package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVMap
import org.h2.mvstore.MVStore
import java.io.*
import java.util.*
import kotlin.reflect.KClass

interface DocumentSerializer {
    fun serialize(document: Any, dout: DataOutputStream)
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
 */
class MvstoreUmn(private val store: MVStore, private val serializer: DocumentSerializer) : Umn {
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

    override fun update(id: UUID, document: Any) {
        map.computeIfPresent(id, { _, _ -> serializer.serialize(document) }) ?: throw IllegalArgumentException("Document with $id does not exist" )
    }

    override fun delete(id: UUID) {
        map.remove(id)
    }

    override fun <K : Comparable<K>> openUtilityMap(name: String): UtilityMap<K> = MvstoreUtilityMap(store, name)
}

/**
 * Thread-safe.
 */
class MvstoreUtilityMap<K: Comparable<K>>(store: MVStore, name: String) : UtilityMap<K> {
    private val map: MVMap<K, ByteArray> = store.openMap("__$name")
    override fun find(key: K): ByteArray? = map[key]
    override fun put(key: K, value: ByteArray?) {
        map[key] = value
    }
    override fun getSmallestKey(): K? = map.firstKey()
    override fun getLargestKey(): K? = map.lastKey()
}
