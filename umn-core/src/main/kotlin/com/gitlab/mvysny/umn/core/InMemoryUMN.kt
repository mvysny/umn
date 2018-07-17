package com.gitlab.mvysny.umn.core

import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * A very simple in-memory storage of objects. Ideal for writing tests. Thread-safe.
 *
 * Since it's in-memory only, it supports all key types: any class classes can act as a key.
 */
class InMemoryUMN : UMN {
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

    override fun <V : Any> find(id: UUID, clazz: KClass<V>): V? = clazz.java.cast(map[id])

    override fun exists(id: UUID): Boolean = map.containsKey(id)

    override fun <V : Any> update(id: UUID, document: V, clazz: KClass<V>) {
        map.computeIfPresent(id, { _, _ -> document }) ?: throw IllegalArgumentException("The document with id $id does not exist")
    }

    override fun delete(id: UUID, clazz: KClass<out Any>) {
        map.remove(id)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <K : Comparable<K>, V> openUtilityMap(name: String): UtilityMap<K, V> =
        utilityMaps.getOrPut(name, { InMemoryUtilityMap<K, V>() }) as UtilityMap<K, V>
}

/**
 * Thread-safe.
 */
class InMemoryUtilityMap<K : Comparable<K>, V> : UtilityMap<K, V> {
    private val map = ConcurrentHashMap<K, V>()

    override fun keyIterator(range: ClosedRange<K>): Iterator<K> = map.keys.filter { key -> key in range }.sorted().iterator()

    override fun find(key: K): V? = map[key]

    override fun put(key: K, value: V?) {
        map.putOrRemove(key, value)
    }

    override fun getSmallestKey(): K? = map.keys.min()

    override fun getLargestKey(): K? = map.keys.max()

    override fun values(): Iterator<V> = map.values.iterator()

    override fun clear() {
        map.clear()
    }

    override fun isEmpty(): Boolean = map.isEmpty()
}
