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
 * The indices are never re-generated on function change; it is imperative that the [IndexDef.keyGetter] function is stable and always returns
 * the same set for given document. It is currently not possible to update an index for all documents; any changes to the [IndexDef.keyGetter]
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
class IndexingUMN(override val delegate: UMN, indices: Set<IndexDef<*, *, *>>) : UMN {

    /**
     * Maps index name to the index implementor.
     */
    private val indexMap: Map<String, IndexImpl<*, *, *>> = indices.associate { it.name to it.toImpl(this) }

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
     * An index definition. Index provides very quick lookup of a document for given key.
     *
     * Typically, index retrieves keys for a document and then maintains the mapping of a key to its document. That's the simple use-case,
     * simply use [simple] to produce indices of this type. That is the most common usage.
     *
     * However, sometimes you don't want to map keys to the document being indexed, but to something else, say to some kind of its parent.
     * This would save you the time which would be otherwise wasted by loading of the document, retrieving its parent ID from the document,
     * and loading the parent.
     *
     * @property K the type of keys. Consult the underlying storage for [UtilityMap] key types. Yet, at least [String] and [Long] must always
     * be supported. The type must be comparable and must properly implement [Any.equals] and [Any.hashCode]. Often the key is just a String,
     * but sometimes it's more complex comparable so that we can select a range of keys and have the values sorted.
     * @property D the type of documents indexed by this index. The indexing function is run on these documents as they are created or updated
     * within the [UMN].
     * @property V the type of values associated with the keys. Typically it's the same as [D]. It's the type of documents for which the
     * [valueIdGetter] provides IDs for.
     * @property name the unique name of the index
     * @property keyGetter computes keys for given document. If no indices are required for this document or if the document is not indexed
     * by this index, just return an empty set.
     * The function must run quick, must be pure and must be consistent in computing the same set of keys for a particular document.
     * @property documentType the type of documents indexed by this index. The [keyGetter] will only be called for documents of this type -
     * all other document types will be ignored and will not be indexed by this index.
     * @property valueType the type of documents associated with the keys. Typically equal to [documentType] for simple indices.
     * @property valueIdGetter which ID to actually associate with the key. Receives the document ID and the document itself. By default simply
     * returns the ID
     */
    class IndexDef<K: Comparable<K>, D: Any, V: Any>(val name: String, val keyGetter: (D) -> Set<K>, val documentType: KClass<D>,
                                             val valueType: KClass<V>, val valueIdGetter: (UUID, D) -> UUID) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as IndexDef<*, *, *>
            if (name != other.name) return false
            return true
        }

        override fun hashCode(): Int = name.hashCode()
        override fun toString(): String = "IndexDef(name='$name')"

        internal fun toImpl(umn: IndexingUMN): IndexImpl<K, D, V> = umn.IndexImpl(this)

        companion object {
            /**
             * Creates a simple index which simply maps keys to the ID of the document.
             * @param name the unique name of the index
             * @param keyGetter computes keys for given document. If no indices are required for this document or if the document is not indexed
             * by this index, just return an empty set.
             * The function must run quick, must be pure and must be consistent in computing the same set of keys for a particular document.
             * @property documentType the type of documents indexed by this index. The [keyGetter] will only be called for documents of this type -
             * all other document types will be ignored and will not be indexed by this index.
             */
            fun <K: Comparable<K>, D: Any> simple(name: String, keyGetter: (D) -> Set<K>, documentType: KClass<D>) : IndexDef<K, D, D> =
                    IndexDef(name, keyGetter, documentType, documentType, { id, _ -> id })
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
     * @property V the type of documents referenced by the keys.
     */
    inner class IndexImpl<K: Comparable<K>, D: Any, V: Any>(val def: IndexDef<K, D, V>) {

        /**
         * Maps key to a set of documents IDs (the [V] documents). Don't use Set since that's serialized using Java serialization; use Arrays
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
        fun findOneByKey(key: K): Pair<UUID, V>? {
            val id: UUID = findOneIDByKey(key) ?: return null
            val doc = delegate.find(id, def.valueType)
            return if (doc == null) null else id to doc
        }

        /**
         * Finds one document matching random key from given set of [keys]. If there are multiple documents, one is picked arbitrarily.
         * @param keys the keys, must not be empty
         */
        fun findOneByKeys(keys: Set<K>): Pair<UUID, V>? {
            require(keys.isNotEmpty()) { "keys is empty" }
            return keys.asSequence().mapNotNull { key -> findOneByKey(key) } .firstOrNull()
        }

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
        fun findAllByKey(key: K): Map<UUID, V> {
            val ids: Array<UUID> = indexStore.find(key) ?: arrayOf()
            val map = mutableMapOf<UUID, V>()
            ids.forEach { id ->
                if (!map.containsKey(id)) {
                    val doc = delegate.find(id, def.valueType)
                    if (doc != null) map[id] = doc
                }
            }
            return map
        }

        /**
         * Returns all documents matching given key [range]. The returned map is ordered - the keys are enumerated in increasing order.
         * @param page the offset and the limit. By default retrieves all matching documents.
         * @return a map of documents and their respective IDs. The map is ordered by the matching keys, ascending. May be empty.
         */
        fun findAllByKeyRange(range: ClosedRange<K>, page: IntRange = 0..Int.MAX_VALUE): Map<UUID, V> {
            if (page.isEmpty() || range.isEmpty()) return mapOf()
            val result = mutableMapOf<UUID, V>()
            var currentIndex = 0
            indexStore.keyIterator(range).forEach { key ->
                val ids: Array<UUID> = indexStore.find(key) ?: arrayOf()
                ids.forEach { id ->
                    if (!result.containsKey(id) && delegate.exists(id)) {
                        if (currentIndex in page) {
                            val doc = delegate.find(id, def.valueType)
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
            if (page.isEmpty() || range.isEmpty()) return setOf()
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
            if (range.isEmpty()) return 0
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
        fun documentIdSequenceByKeyRange(range: ClosedRange<K>): Sequence<UUID> {
            if (range.isEmpty()) return listOf<UUID>().asSequence()
            return indexStore.keyIterator(range).asSequence().mapNotNull { key -> indexStore.find(key) } .flatMap { it.asSequence() }
        }

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
        fun allDocumentSequence(): Sequence<Pair<UUID, V>> = allDocumentIdSequence().mapNotNull { id ->
            val doc = delegate.find(id, def.valueType)
            if (doc == null) null else id to doc
        }

        internal fun onCreated(id: UUID, document: Any) {
            val docType = def.documentType.java
            if (!docType.isInstance(document)) return
            val doc = docType.cast(document)
            val valueId = def.valueIdGetter(id, doc)
            registerUnregisterIndices(valueId, def.keyGetter(doc), setOf())
        }

        internal fun onDeleted(id: UUID, document: Any) {
            val docType = def.documentType.java
            if (!docType.isInstance(document)) return
            val doc = docType.cast(document)
            val unregisterIndices: Set<K> = def.keyGetter(doc)
            val valueId = def.valueIdGetter(id, doc)
            registerUnregisterIndices(valueId, setOf(), unregisterIndices)
        }

        internal fun onUpdate(id: UUID, newDoc: Any, oldDoc: Any) {
            val docType = def.documentType.java
            val newDocOfCorrectType = docType.isInstance(newDoc)
            val oldDocOfCorrectType = docType.isInstance(oldDoc)
            if (!newDocOfCorrectType && !oldDocOfCorrectType) {
                // do nothing
            } else if (newDocOfCorrectType && !oldDocOfCorrectType) {
                val doc = docType.cast(newDoc)
                val registerIndices = def.keyGetter(doc)
                val valueId = def.valueIdGetter(id, doc)
                registerUnregisterIndices(valueId, registerIndices, setOf())
            } else if (!newDocOfCorrectType && oldDocOfCorrectType) {
                val doc = docType.cast(oldDoc)
                val unregisterIndices = def.keyGetter(doc)
                val valueId = def.valueIdGetter(id, doc)
                registerUnregisterIndices(valueId, setOf(), unregisterIndices)
            } else {
                val oldDocD = docType.cast(oldDoc)
                val newDocD = docType.cast(newDoc)
                val oldValueId = def.valueIdGetter(id, oldDocD)
                val newValueId = def.valueIdGetter(id, newDocD)
                val unregisterIndices = def.keyGetter(oldDocD)
                val registerIndices = def.keyGetter(newDocD)
                if (oldValueId == newValueId) {
                    registerUnregisterIndices(newValueId, registerIndices, unregisterIndices)
                } else {
                    registerUnregisterIndices(oldValueId, setOf(), unregisterIndices)
                    registerUnregisterIndices(newValueId, registerIndices, setOf())
                }
            }
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
    fun <I: Comparable<I>, D: Any, V: Any> getIndex(name: String): IndexImpl<I, D, V> {
        return indexMap[name] as IndexImpl<I, D, V>? ?: throw IllegalStateException("Unknown index name $name, available indices: ${indexMap.keys}")
    }

    @Suppress("UNCHECKED_CAST")
    fun <I: Comparable<I>, D: Any, V: Any> getIndex(index: IndexDef<I, D, V>): IndexImpl<I, D, V> {
        return indexMap[index.name] as IndexImpl<I, D, V>? ?: throw IllegalStateException("Unknown index $index, available indices: ${indexMap.keys}")
    }


    // @todo implement paged document range retrieval

    // @todo implement unique index
}
