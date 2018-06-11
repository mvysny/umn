package com.gitlab.mvysny.umn.core

import java.util.*
import kotlin.reflect.KClass

/**
 * Generates indices for documents. The purpose of this class is multiple:
 * * To look up multiple documents stored under the same key very quickly
 * * To have an UNIQUE constraint
 * * The keys are [Comparable] - it must be possible to quickly select a list of documents from given key range, sorted by given key;
 * the list must be pageable.
 *
 * Custom key classes should be supported, to allow quick lookup. If that is not possible, at least a [List] of basic [Comparable]s must be
 * supported. For example you can have a key with two parts: the `key` itself which is used to range-select the values, and the `ordering`
 * part which is then used to order the paged results.
 *
 * The indices are never re-generated for all documents. It is imperative that the [indexGenerator] function is stable and always returns
 * the same set for given document. It is currently not possible to update an index for all documents; any changes to the [indexGenerator]
 * function will be applied to newly inserted or updated documents only.
 *
 * ### Implementation
 * Intercepts all calls and passes them to underlying map, but updates the indices on the fly. Currently thread unsafe.
 *
 * @param I the type of the index values. Consult the underlying storage for [UtilityMap] key types. Yet, at least [String] and [Long] must always
 * be supported. The type must be comparable and must properly implement [Any.equals] and [Any.hashCode]. Often the key is just a String,
 * but sometimes it's more complex comparable so that we can select a range of keys and have the values sorted.
 * @param name the index name. To have multiple indices, just chain multiple classes (todo maybe a list would be better so that the call stacks
 * won't become too long)
 * @property indexGenerator generates IDs for stored documents. If no indices are required for this document, just return an empty set.
 */
class IndexingUmn<I: Comparable<I>>(name: String, private val delegate: Umn, private val indexGenerator: (Any) -> Set<I>) : Umn {
    /**
     * Maps String index to a set of documents.
     */
    private val indexStore: UtilityMap<I, MutableSet<UUID>> = delegate.openUtilityMap("INDEX_$name")

    override fun create(document: Any): UUID {
        val id = delegate.create(document)
        registerUnregisterIndices(id, indexGenerator(document), setOf())
        return id
    }

    private fun registerUnregisterIndices(id: UUID, register: Set<I>, unregister: Set<I>) {
        val indicesToRegister: Set<I> = register - unregister
        val indicesToUnregister: Set<I> = unregister - register
        indicesToRegister.forEach { index ->
            var array: MutableSet<UUID>? = indexStore.find(index)
            if (array == null) {
                array = mutableSetOf(id)
                indexStore.put(index, array)
            } else if (!array.contains(id)) {
                array.add(id)
                indexStore.put(index, array)
            }
        }
        indicesToUnregister.forEach { index ->
            val array = indexStore.find(index)
            if (array != null && array.contains(id)) {
                array.remove(id)
                indexStore.put(index, if (array.isEmpty()) null else array)
            }
        }
    }

    override fun <V : Any> find(id: UUID, clazz: KClass<V>): V? = delegate.find(id, clazz)

    override fun exists(id: UUID) = delegate.exists(id)

    override fun delete(id: UUID, clazz: KClass<out Any>) {
        val document: Any = find(id, clazz) ?: return
        val indices = indexGenerator(document)
        delegate.delete(id, clazz)
        registerUnregisterIndices(id, setOf(), indices)
    }

    override fun <V: Any> update(id: UUID, document: V, clazz: KClass<V>) {
        val oldDoc: V = get(id, clazz)
        delegate.update(id, document, clazz)
        registerUnregisterIndices(id, indexGenerator(document), indexGenerator(oldDoc))
    }

    override fun <K : Comparable<K>, V> openUtilityMap(name: String): UtilityMap<K, V> = delegate.openUtilityMap(name)

    override fun close() {
        delegate.close()
    }

    fun <V: Any> findOneByKey(key: I, clazz: KClass<V>): V? {
        val ids: Set<UUID> = indexStore.find(key) ?: return null
        val id: UUID = ids.firstOrNull() ?: return null
        return delegate.find(id, clazz)
    }

    // @todo implement document range retrieval
}
