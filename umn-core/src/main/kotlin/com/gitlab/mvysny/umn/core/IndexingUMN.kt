package com.gitlab.mvysny.umn.core

import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.LinkedHashMap
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
 * The indices are never re-generated on function change; it is imperative that the [IndexDef.indexGenerator] function is stable and always returns
 * the same set for given document. It is currently not possible to update an index for all documents; any changes to the [IndexDef.indexGenerator]
 * function will be applied to newly inserted or updated documents only. You can not currently create a new index over a database with
 * existing documents.
 *
 * ### Implementation
 * Intercepts all calls and passes them to underlying map, but updates the indices on the fly.
 *
 * @property delegate delegate all calls to the underlying UMN storage. Typically either one of [InMemoryUMN] or [MvstoreUMN].
 * @param indices the indices definition. WARNING: the same set of indices must be provided upon every UMN open! New indices not yet present
 * in the database will not reindex existing documents to build the index and searches will not find those documents. Changing the indexing
 * function will not reindex existing documents. Removing an index will not clear the associated index map.
 */
class IndexingUMN(override val delegate: UMN, indices: Set<IndexDef<*>>) : UMN {

    /**
     * Maps index name to the index implementor.
     */
    private val indexMap: Map<String, IndexImpl<*>> = indices.associate { it.name to it.toImpl(this) }

    override fun create(document: Any, id: UUID): Boolean {
        return indexUpdaterGlobalLock.withLock {
            val created = delegate.create(document, id)
            if (created) {
                indexMap.values.forEach { it.onCreated(id, document) }
            }
            created
        }
    }

    override fun create(document: Any): UUID {
        return indexUpdaterGlobalLock.withLock {
            val id = delegate.create(document)
            indexMap.values.forEach { it.onCreated(id, document) }
            id
        }
    }

    /**
     * An index definition.
     * @property I the type of the index values. Consult the underlying storage for [UtilityMap] key types. Yet, at least [String] and [Long] must always
     * be supported. The type must be comparable and must properly implement [Any.equals] and [Any.hashCode]. Often the key is just a String,
     * but sometimes it's more complex comparable so that we can select a range of keys and have the values sorted.
     * @property name the unique name of the index
     * @property indexGenerator generates IDs for stored documents. If no indices are required for this document, just return an empty set.
     * The function must run quick, must be pure and must be consistent in computing the same set of keys for a particular document.
     */
    class IndexDef<I: Comparable<I>>(val name: String, val indexGenerator: (Any) -> Set<I>) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as IndexDef<*>
            if (name != other.name) return false
            return true
        }

        override fun hashCode(): Int = name.hashCode()
        override fun toString(): String = "IndexDef(name='$name')"

        internal fun toImpl(umn: IndexingUMN): IndexImpl<I> = umn.IndexImpl(this)
    }

    /**
     * Prevents multiple threads from updating indices of a particular document at the same time.
     *
     * Pozor! Nestaci zamknut lock iba v [registerUnregisterIndices]: ak naraz updatuje jeden dokument viacero threadov,
     * operacie zapisu mozu bezat v inom poradi ako update indexov! Preto treba zamknut komplet cele metody.
     */
    private val indexUpdaterGlobalLock = ReentrantLock()

    inner class IndexImpl<I: Comparable<I>>(val def: IndexDef<I>) {

        /**
         * Maps String index to a set of documents. Don't use Set since that's serialized using Java serialization; use Arrays
         * since that's serialized more effectively; see [ObjectDataType#TYPE_ARRAY] for more info.
         */
        private val indexStore: UtilityMap<I, Array<UUID>> = delegate.openUtilityMap("INDEX2_${def.name}")

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
                    val newArray = array.filterNot { it == id }.toTypedArray()
                    indexStore.put(index, if (newArray.isEmpty()) null else array)
                }
            }
        }

        /**
         * Finds one document matching given [key]. If there are multiple documents, one is picked arbitrarily.
         * @param clazz deserialize the document to this class, using [UMN.find].
         */
        fun <V: Any> findOneByKey(key: I, clazz: KClass<V>): Pair<UUID, V>? {
            val ids: Array<UUID> = indexStore.find(key) ?: return null
            val id: UUID = ids.firstOrNull() ?: return null
            val doc = delegate.find(id, clazz)
            return if (doc == null) null else id to doc
        }

        /**
         * Finds all documents matching given [key].
         * @param clazz deserialize the document to this class, using [UMN.find].
         */
        fun <V: Any> findAllByKey(key: I, clazz: KClass<V>): Map<UUID, V> {
            val ids: Array<UUID> = indexStore.find(key) ?: arrayOf()
            val map = mutableMapOf<UUID, V>()
            ids.forEach { id ->
                if (!map.containsKey(id)) {
                    val doc = delegate.find(id, clazz)
                    if (doc != null) map[id] = doc
                }
            }
            return map
        }

        /**
         * Returns all documents matching given key [range].
         * @param clazz deserialize the documents to this class, using [UMN.find].
         */
        fun <V: Any> findAllByKeyRange(range: ClosedRange<I>, clazz: KClass<V>): Map<UUID, V> {
            val result = mutableMapOf<UUID, V>()
            indexStore.keyIterator(range).forEach { key ->
                val ids: Array<UUID> = indexStore.find(key) ?: arrayOf()
                ids.forEach { id ->
                    if (!result.containsKey(id)) {
                        val doc = delegate.find(id, clazz)
                        if (doc != null) result[id] = doc
                    }
                }
            }
            return result
        }

        internal fun onCreated(id: UUID, document: Any) {
            registerUnregisterIndices(id, def.indexGenerator(document), setOf())
        }

        internal fun onDeleted(id: UUID, document: Any) {
            val indices = def.indexGenerator(document)
            registerUnregisterIndices(id, setOf(), indices)
        }

        internal fun onUpdate(id: UUID, newDoc: Any, oldDoc: Any) {
            registerUnregisterIndices(id, def.indexGenerator(newDoc), def.indexGenerator(oldDoc))
        }
    }

    override fun <V : Any> find(id: UUID, clazz: KClass<V>): V? = delegate.find(id, clazz)

    override fun exists(id: UUID) = delegate.exists(id)

    override fun delete(id: UUID, clazz: KClass<out Any>) {
        indexUpdaterGlobalLock.withLock {
            val document: Any = find(id, clazz) ?: return
            delegate.delete(id, clazz)
            indexMap.values.forEach { it.onDeleted(id, document) }
        }
    }

    override fun <V: Any> update(id: UUID, document: V, clazz: KClass<V>) {
        indexUpdaterGlobalLock.withLock {
            val oldDoc: V = get(id, clazz)
            delegate.update(id, document, clazz)
            indexMap.values.forEach { it.onUpdate(id, document, oldDoc) }
        }
    }

    override fun <K : Comparable<K>, V> openUtilityMap(name: String): UtilityMap<K, V> = delegate.openUtilityMap(name)

    override fun close() {
        delegate.close()
    }

    /**
     * Returns a particular index with given [name], failing if no such index was provided to the constructor.
     */
    @Suppress("UNCHECKED_CAST")
    fun <I: Comparable<I>> getIndex(name: String): IndexImpl<I> {
        return indexMap[name] as IndexImpl<I>? ?: throw IllegalStateException("Unknown index name $name, available indices: ${indexMap.keys}")
    }

    // @todo implement paged document range retrieval

    // @todo implement unique index
}
