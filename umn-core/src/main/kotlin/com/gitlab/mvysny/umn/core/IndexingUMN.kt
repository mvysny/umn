package com.gitlab.mvysny.umn.core

import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
 * The indices are never re-generated on function change; it is imperative that the [indexGenerator] function is stable and always returns
 * the same set for given document. It is currently not possible to update an index for all documents; any changes to the [indexGenerator]
 * function will be applied to newly inserted or updated documents only. You can not currently create a new index over a database with
 * existing documents.
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
class IndexingUMN<I: Comparable<I>>(name: String, override val delegate: UMN, private val indexGenerator: (Any) -> Set<I>) : UMN {
    override fun create(document: Any, id: UUID) {
        indexUpdaterGlobalLock.withLock {
            delegate.create(document, id)
            registerUnregisterIndices(id, indexGenerator(document), setOf())
        }
    }

    /**
     * Maps String index to a set of documents. Don't use Set since that's serialized using Java serialization; use Arrays
     * since that's serialized more effectively; see [ObjectDataType#TYPE_ARRAY] for more info.
     */
    private val indexStore: UtilityMap<I, Array<UUID>> = delegate.openUtilityMap("INDEX_$name")

    override fun create(document: Any): UUID {
        return indexUpdaterGlobalLock.withLock {
            val id = delegate.create(document)
            registerUnregisterIndices(id, indexGenerator(document), setOf())
            id
        }
    }

    /**
     * Prevents multiple threads from updating indices of a particular document at the same time.
     *
     * Pozor! Nestaci zamknut lock iba v [registerUnregisterIndices]: ak naraz updatuje jeden dokument viacero threadov,
     * operacie zapisu mozu bezat v inom poradi ako update indexov! Preto treba zamknut komplet cele metody.
     */
    private val indexUpdaterGlobalLock = ReentrantLock()

    private fun registerUnregisterIndices(id: UUID, register: Set<I>, unregister: Set<I>) {
        check(indexUpdaterGlobalLock.isHeldByCurrentThread) { "lock not held!" }
        val indicesToRegister: Set<I> = register - unregister
        val indicesToUnregister: Set<I> = unregister - register
        indicesToRegister.forEach { index ->
            var array: Array<UUID>? = indexStore.find(index)
            if (array == null) {
                array = arrayOf(id)
                indexStore.put(index, array)
            } else if (!array.contains(id)) {
                array += id
                indexStore.put(index, array)
            }
        }
        indicesToUnregister.forEach { index ->
            val array = indexStore.find(index)
            if (array != null && array.contains(id)) {
                val newArray = array.filterNot { it == id } .toTypedArray()
                indexStore.put(index, if (newArray.isEmpty()) null else array)
            }
        }
    }

    override fun <V : Any> find(id: UUID, clazz: KClass<V>): V? = delegate.find(id, clazz)

    override fun exists(id: UUID) = delegate.exists(id)

    override fun delete(id: UUID, clazz: KClass<out Any>) {
        indexUpdaterGlobalLock.withLock {
            val document: Any = find(id, clazz) ?: return
            val indices = indexGenerator(document)
            delegate.delete(id, clazz)
            registerUnregisterIndices(id, setOf(), indices)
        }
    }

    override fun <V: Any> update(id: UUID, document: V, clazz: KClass<V>) {
        indexUpdaterGlobalLock.withLock {
            val oldDoc: V = get(id, clazz)
            delegate.update(id, document, clazz)
            registerUnregisterIndices(id, indexGenerator(document), indexGenerator(oldDoc))
        }
    }

    override fun <K : Comparable<K>, V> openUtilityMap(name: String): UtilityMap<K, V> = delegate.openUtilityMap(name)

    override fun close() {
        delegate.close()
    }

    /**
     * Finds one document matching given [key]. If there are multiple documents, one is picked arbitrarily.
     * @param clazz deserialize the document to this class, using [UMN#find].
     */
    fun <V: Any> findOneByKey(key: I, clazz: KClass<V>): V? {
        val ids: Array<UUID> = indexStore.find(key) ?: return null
        val id: UUID = ids.firstOrNull() ?: return null
        return delegate.find(id, clazz)
    }

    /**
     * Returns all documents matching given key [range].
     * @param clazz deserialize the documents to this class, using [UMN#find].
     */
    fun <V: Any> findAllByKeyRange(range: ClosedRange<I>, clazz: KClass<V>): List<V> {
        val idsEncountered = mutableSetOf<UUID>()
        val result = LinkedList<V>()
        indexStore.keyIterator(range).forEach { key ->
            val ids: Array<UUID> = indexStore.find(key) ?: arrayOf()
            ids.forEach { id ->
                if (!idsEncountered.contains(id)) {
                    val doc = delegate.find(id, clazz)
                    if (doc != null) result.add(doc)
                }
            }
            idsEncountered.addAll(ids)
        }
        return result.toList()
    }

    // @todo implement paged document range retrieval

    // @todo implement unique index
}
