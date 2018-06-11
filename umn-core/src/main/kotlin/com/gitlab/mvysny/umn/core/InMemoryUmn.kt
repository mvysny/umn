package com.gitlab.mvysny.umn.core

import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * A very simple in-memory storage of objects. Ideal for writing tests. Thread-safe.
 */
class InMemoryUmn : Umn {
    override fun close() {
        map.clear()
        utilityMaps.clear()
    }

    private val map = ConcurrentHashMap<UUID, Any>()
    private val utilityMaps = ConcurrentHashMap<String, UtilityMap<*>>()

    override fun create(document: Any): UUID {
        val id = UUID.randomUUID()
        map[id] = document
        return id
    }

    override fun <V : Any> find(id: UUID, clazz: KClass<V>): V? = clazz.java.cast(map[id])

    override fun exists(id: UUID): Boolean = map.contains(id)

    override fun update(id: UUID, document: Any) {
        map.computeIfPresent(id, { _, _ -> document }) ?: throw IllegalArgumentException("The document with id $id does not exist")
    }

    override fun delete(id: UUID) {
        map.remove(id)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <K : Comparable<K>> openUtilityMap(name: String): UtilityMap<K> =
        utilityMaps.getOrPut(name, { InMemoryUtilityMap<K>() }) as UtilityMap<K>
}

/**
 * Thread-safe.
 */
class InMemoryUtilityMap<K: Comparable<K>> : UtilityMap<K> {
    private val map = ConcurrentHashMap<K, ByteArray>()

    override fun find(key: K): ByteArray? = map[key]

    override fun put(key: K, value: ByteArray?) {
        if (value == null) {
            map.remove(key)
        } else {
            map[key] = value
        }
    }

    override fun getSmallestKey(): K? = map.keys().toList().min()

    override fun getLargestKey(): K? = map.keys().toList().max()
}
