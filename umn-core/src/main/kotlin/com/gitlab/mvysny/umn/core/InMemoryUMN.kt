@file:Suppress("DEPRECATION")

package com.gitlab.mvysny.umn.core

import org.h2.mvstore.type.DataType
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * A very simple in-memory storage of objects. Thread-safe.
 *
 * Deprecated: it behaves unlike [MvstoreUMN] where tests most need it to match. It stores the live
 * objects, so a document or an index key that doesn't serialize passes here and fails in production,
 * and a document changed after [create] changes the stored one; [InMemoryUtilityMap.keyIterator]
 * returns the keys unordered when given no range. [MvstoreUMN.inMemory] has none of that.
 */
@Deprecated(
    "Behaves unlike MvstoreUMN: no serialization, unordered keys. Use MvstoreUMN.inMemory().",
    ReplaceWith("MvstoreUMN.inMemory(serializer, typeToClassResolver)")
)
public class InMemoryUMN(override val typeToClassResolver: TypeToClassResolver,
                  private val serializer: DocumentSerializer) : UMN {
    override val delegate: UMN?
        get() = null

    override fun create(document: Any, id: UUID): Boolean {
        return map.putIfAbsent(id, document) == null
    }

    override fun close() {
        map.clear()
        utilityMaps.clear()
    }

    private val map = ConcurrentHashMap<UUID, Any>()
    private val utilityMaps = ConcurrentHashMap<String, UtilityMap<*, *>>()

    override fun create(document: Any): UUID {
        val id = UUID.randomUUID()
        map[id] = document
        return id
    }

    override fun <V : Any> find(id: UUID, clazz: KClass<V>): V? = clazz.javaObjectType.cast(map[id])

    override fun exists(id: UUID): Boolean = map.containsKey(id)

    override fun update(id: UUID, document: Any) {
        map.updateIfPresent(id) { _, _ -> document } ?: throw IllegalArgumentException("The document with id $id does not exist")
    }

    override fun delete(id: UUID) {
        map.remove(id)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <K : Comparable<K>, V> openUtilityMap(name: String, keyType: DataType?, valueType: DataType?): UtilityMap<K, V> =
        utilityMaps.getOrPut(name) { InMemoryUtilityMap<K, V>() } as UtilityMap<K, V>

    override fun dropUtilityMap(name: String) {
        utilityMaps.remove(name)
    }

    override fun utilityMapNames(): Set<String> = utilityMaps.keys.toSet()

    override fun findSerialized(id: UUID): SerializedDocument? {
        val doc = find(id, Any::class) ?: return null
        val type = typeToClassResolver.toType(doc.javaClass.kotlin)
        return serializeData {
            writeShort(type.toInt())
            serializer.serialize(doc, this)
        }
    }

    override fun getSequenceOfIDs(): Sequence<UUID> = map.keys.toList().asSequence()
}

/**
 * Thread-safe.
 */
@Deprecated("InMemoryUMN's utility map; use MvstoreUMN.inMemory(), whose utility maps order their keys.")
public class InMemoryUtilityMap<K : Comparable<K>, V> : UtilityMap<K, V> {
    override fun keyCount(): Int = map.size

    override fun keyPage(indexRange: IntRange): List<K> {
        if (indexRange.isEmpty()) return listOf()
        require(indexRange.start >= 0) { "$indexRange contains negative indices" }
        val allKeys = map.keys.toList().sorted()
        return allKeys.slice(indexRange.first..minOf(indexRange.last, allKeys.lastIndex))
    }

    override fun containsKey(key: K): Boolean = map.containsKey(key)

    private val map = ConcurrentHashMap<K, V>()

    override fun keyIterator(range: ClosedRange<K>?): Sequence<K> {
        if (range == null) return map.keys.asSequence()
        return map.keys.filter { key -> key in range }.sorted().asSequence()
    }

    override fun find(key: K): V? = map[key]

    override fun put(key: K, value: V?) {
        map.putOrRemove(key, value)
    }

    override fun getSmallestKey(): K? = map.keys.minOrNull()

    override fun getLargestKey(): K? = map.keys.maxOrNull()

    override fun values(): Iterator<V> = map.entries.sortedBy { it.key } .map { it.value } .iterator()

    override fun clear() {
        map.clear()
    }

    override fun isEmpty(): Boolean = map.isEmpty()

    override fun lowerKey(key: K): K? = map.keys.asSequence().filter { it < key }.maxOrNull()
}
