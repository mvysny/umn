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
class IndexingUMN(override val delegate: UMN, indices: Set<IndexDef<*, *>>) : UMN {

    /**
     * Maps index name to the index implementor.
     */
    private val indexMap: Map<String, IndexImpl<*, *>> = indices.associate { it.name to it.toImpl(this) }

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
     * @property K the type of keys. Consult the underlying storage for [UtilityMap] key types. Yet, at least [String] and [Long] must always
     * be supported. The type must be comparable and must properly implement [Any.equals] and [Any.hashCode]. Often the key is just a String,
     * but sometimes it's more complex comparable so that we can select a range of keys and have the values sorted.
     * @property D the type of documents indexed by this index.
     * @property name the unique name of the index
     * @property indexGenerator generates indices for documents. If no indices are required for this document or if the document is not indexed
     * by this index, just return an empty set.
     * The function must run quick, must be pure and must be consistent in computing the same set of keys for a particular document.
     * @property documentType the type of documents indexed by this index. The [indexGenerator] will only be called for documents of this type -
     * all other document types will be ignored and will not be indexed by this index.
     */
    class IndexDef<K: Comparable<K>, D: Any>(val name: String, val indexGenerator: (D) -> Set<K>, val documentType: KClass<D>) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as IndexDef<*, *>
            if (name != other.name) return false
            return true
        }

        override fun hashCode(): Int = name.hashCode()
        override fun toString(): String = "IndexDef(name='$name')"

        internal fun toImpl(umn: IndexingUMN): IndexImpl<K, D> = umn.IndexImpl(this)

        internal fun indicesFor(document: Any): Set<K> {
            if (!documentType.java.isInstance(document)) return setOf()
            return indexGenerator(documentType.java.cast(document))
        }
    }

    /**
     * Prevents multiple threads from updating indices of a particular document at the same time.
     *
     * Pozor! Nestaci zamknut lock iba v [registerUnregisterIndices]: ak naraz updatuje jeden dokument viacero threadov,
     * operacie zapisu mozu bezat v inom poradi ako update indexov! Preto treba zamknut komplet cele metody.
     */
    private val indexUpdaterGlobalLock = ReentrantLock()

    /**
     * @property K the type of keys (objects acting as index)
     * @property D the type of documents indexed by this index.
     */
    inner class IndexImpl<K: Comparable<K>, D: Any>(val def: IndexDef<K, D>) {

        /**
         * Maps String index to a set of documents. Don't use Set since that's serialized using Java serialization; use Arrays
         * since that's serialized more effectively; see [ObjectDataType#TYPE_ARRAY] for more info.
         */
        private val indexStore: UtilityMap<K, Array<UUID>> = delegate.openUtilityMap("INDEX2_${def.name}")

        private fun registerUnregisterIndices(id: UUID, register: Set<K>, unregister: Set<K>) {
            check(indexUpdaterGlobalLock.isHeldByCurrentThread) { "lock not held!" }
            val indicesToRegister: Set<K> = register - unregister
            val indicesToUnregister: Set<K> = unregister - register
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
                    indexStore.put(index, if (newArray.isEmpty()) null else newArray)
                }
            }
        }

        /**
         * Finds one document matching given [key]. If there are multiple documents, one is picked arbitrarily.
         */
        fun findOneByKey(key: K): Pair<UUID, D>? {
            val id: UUID = findOneIDByKey(key) ?: return null
            val doc = delegate.find(id, def.documentType)
            return if (doc == null) null else id to doc
        }

        /**
         * Finds one document matching random key from given set of [keys]. If there are multiple documents, one is picked arbitrarily.
         */
        fun findOneByKeys(keys: Set<K>): Pair<UUID, D>? = keys.asSequence().mapNotNull { key -> findOneByKey(key) } .firstOrNull()

        /**
         * Finds ID of one document matching given [key]. If there are multiple documents, one is picked arbitrarily. If no such document
         * exists, returns null.
         */
        fun findOneIDByKey(key: K): UUID? = indexStore.find(key)?.firstOrNull()

        /**
         * Finds all document IDs matching given [key].
         */
        fun findAllIDByKey(key: K): Set<UUID> = indexStore.find(key)?.toSet() ?: setOf()

        /**
         * Finds all documents matching given [key].
         * @param clazz deserialize the document to this class, using [UMN.find].
         */
        fun findAllByKey(key: K): Map<UUID, D> {
            val ids: Array<UUID> = indexStore.find(key) ?: arrayOf()
            val map = mutableMapOf<UUID, D>()
            ids.forEach { id ->
                if (!map.containsKey(id)) {
                    val doc = delegate.find(id, def.documentType)
                    if (doc != null) map[id] = doc
                }
            }
            return map
        }

        /**
         * Returns all documents matching given key [range]. The returned map is ordered - the keys are enumerated in increasing order.
         * @param clazz deserialize the documents to this class, using [UMN.find].
         * @param page the offset and the limit. By default retrieves all matching documents.
         * @return a map of documents and their respective IDs. The map is ordered by the matching keys, ascending. May be empty.
         */
        fun <V: Any> findAllByKeyRange(range: ClosedRange<K>, clazz: KClass<V>, page: IntRange = 0..Int.MAX_VALUE): Map<UUID, V> {
            val result = mutableMapOf<UUID, V>()
            var currentIndex = 0
            indexStore.keyIterator(range).forEach { key ->
                val ids: Array<UUID> = indexStore.find(key) ?: arrayOf()
                ids.forEach { id ->
                    if (!result.containsKey(id) && delegate.exists(id)) {
                        if (currentIndex in page) {
                            val doc = delegate.find(id, clazz)
                            if (doc != null) result[id] = doc
                        }
                        currentIndex++
                        if (currentIndex > page.last) return result
                    }
                }
            }
            return result
        }

        /**
         * Returns all document IDs matching given key [range]. The returned map is ordered - the keys are enumerated in increasing order.
         * @param page the offset and the limit. By default retrieves all matching documents.
         * @return a set of document IDs. The set is ordered by the matching keys, ascending. May be empty.
         */
        fun findAllIDsByKeyRange(range: ClosedRange<K>, page: IntRange = 0..Int.MAX_VALUE): Set<UUID> {
            val result = mutableSetOf<UUID>()
            var currentIndex = 0
            indexStore.keyIterator(range).forEach { key ->
                val ids: Array<UUID> = indexStore.find(key) ?: arrayOf()
                ids.forEach { id ->
                    if (!result.contains(id)) {
                        if (currentIndex in page) {
                            result.add(id)
                        }
                        currentIndex++
                        if (currentIndex > page.last) return result
                    }
                }
            }
            return result
        }

        /**
         * Counts all documents keyed with keys matching given [range].
         * @return count of matching documents, 0 or greater.
         */
        fun getCountByKeyRange(range: ClosedRange<K>): Int {
            val encountered = mutableSetOf<UUID>()
            var count = 0
            indexStore.keyIterator(range).forEach { key ->
                val ids: Array<UUID> = indexStore.find(key) ?: arrayOf()
                ids.forEach { id ->
                    if (encountered.add(id) && delegate.exists(id)) {
                        count++
                    }
                }
            }
            return count
        }

        // @todo mavi unique index size can simply be computed as indexStore.size()
        fun getSize(): Int = indexStore.values().asSequence().sumBy { it.size }

        /**
         * Returns a sequence which lazily iterates over given range of keys. Does not support paging but has lower memory footprint than
         * [findAllIDsByKeyRange].
         */
        fun documentIdSequenceByKeyRange(range: ClosedRange<K>): Sequence<UUID> =
            indexStore.keyIterator(range).asSequence().mapNotNull { key -> indexStore.find(key) } .flatMap { it.asSequence() }

        /**
         * Iterates all UUIDs of all indexed documents (documents for which the indexing function returned a non-null non-empty set).
         * It is safe to modify the index (e.g. deleting documents) while iterating.
         *
         * This function is faster than [allDocumentSequence] since it doesn't load the documents.
         */
        fun allDocumentIdSequence(): Sequence<UUID> = indexStore.values().asSequence().flatMap { it.asSequence() }

        /**
         * Returns a sequence which returns all indexed documents (documents for which the indexing function returned a non-null non-empty set)
         * and their IDs. It is safe to modify the index (e.g. deleting documents) while iterating.
         *
         * This function has higher overhead than [allDocumentIdSequence] since this function also loads the documents.
         */
        fun <V: Any> allDocumentSequence(clazz: KClass<V>): Sequence<Pair<UUID, V>> = allDocumentIdSequence().mapNotNull { id ->
            val doc = delegate.find(id, clazz)
            if (doc == null) null else id to doc
        }

        internal fun onCreated(id: UUID, document: Any) {
            registerUnregisterIndices(id, def.indicesFor(document), setOf())
        }

        internal fun onDeleted(id: UUID, document: Any) {
            val indices = def.indicesFor(document)
            registerUnregisterIndices(id, setOf(), indices)
        }

        internal fun onUpdate(id: UUID, newDoc: Any, oldDoc: Any) {
            registerUnregisterIndices(id, def.indicesFor(newDoc), def.indicesFor(oldDoc))
        }

        /**
         * Checks whether there are no documents in this index.
         */
        fun isEmpty(): Boolean = indexStore.isEmpty()
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
    fun <I: Comparable<I>, D: Any> getIndex(name: String): IndexImpl<I, D> {
        return indexMap[name] as IndexImpl<I, D>? ?: throw IllegalStateException("Unknown index name $name, available indices: ${indexMap.keys}")
    }

    @Suppress("UNCHECKED_CAST")
    fun <I: Comparable<I>, D: Any> getIndex(index: IndexDef<I, D>): IndexImpl<I, D> {
        return indexMap[index.name] as IndexImpl<I, D>? ?: throw IllegalStateException("Unknown index $index, available indices: ${indexMap.keys}")
    }


    // @todo implement paged document range retrieval

    // @todo implement unique index
}
