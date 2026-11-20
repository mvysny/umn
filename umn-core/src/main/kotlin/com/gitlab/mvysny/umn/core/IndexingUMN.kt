package com.gitlab.mvysny.umn.core

import com.gitlab.mvysny.umn.core.IndexingUMN.IndexDef
import com.gitlab.mvysny.umn.core.IndexingUMN.IndexDef.Companion.simple
import org.h2.mvstore.type.DataType
import org.slf4j.LoggerFactory
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
 * The constructor builds every index not built yet over the stored documents: one walk over all of them, blocking, so the first
 * open after adding an index reads the whole database, and holds the new indices' entries in memory until the walk ends. An index
 * once built is never rebuilt: to change its [IndexDef.keyGetter] or key type, give it a new name. `D_build_index_on_open`.
 *
 * ### Implementation
 * Intercepts all calls and passes them to underlying map, but updates the indices on the fly.
 *
 * Each key holds an array of the IDs it indexes, rewritten whole on every add or remove, so a write costs as much as the
 * documents sharing its key. Where many would, make the key tell them apart: `CompositeKey.of(categoryId, ordinal)`, queried by
 * [For.prefix]`(CompositeKey.of(categoryId))`.
 *
 * Not atomic across a kill: the document and its index entries are separate writes, and a store landing between them may keep
 * either without the other (`R_mvstore_store_per_map`), for good, since a built index is never rebuilt. Replaying the record
 * doesn't heal it: [create] skips an existing document, [update] diffs against the stored one, [delete] returns on a missing
 * one. Writing the entries first would let a replay heal it, but readers would see a key ahead of its document on every
 * write. A change through a [com.gitlab.mvysny.umn.sync.SyncingUMN] commits both before it returns, narrowing the gap to a
 * kill within that commit.
 *
 * @property delegate delegate all calls to the underlying UMN storage. Typically an [MvstoreUMN].
 * @param indices pass the same set on every open: an index left out has its entries cleared, and is built anew once passed again.
 */
public class IndexingUMN(override val delegate: UMN, indices: Set<IndexDef<*, *, *>>) : UMN {

    /**
     * Prevents multiple threads from updating indices of a particular document at the same time.
     *
     * Pozor! Nestaci zamknut lock iba v [IndexImpl.registerUnregisterIndices]: ak naraz updatuje jeden dokument viacero threadov,
     * operacie zapisu mozu bezat v inom poradi ako update indexov! Preto treba zamknut komplet cele metody.
     */
    private val indexUpdaterGlobalLock = ReentrantLock()

    /**
     * Maps index name to the index implementor.
     */
    private val indexMap: Map<String, IndexImpl<*, *, *>> = indices.associate { it.name to it.toImpl(this) }

    /**
     * Each index's build state, by name: `true` built; `false` a build begun, maybe cut short by a crash; none never built, as
     * every index of a store 0.17 or older last opened. An index's entries never outlive its state, the only way
     * [clearDroppedIndices] finds them.
     */
    private val indexState: UtilityMap<String, Boolean> = delegate.openUtilityMap("INDEX_STATE")

    init {
        indexUpdaterGlobalLock.withLock {
            clearDroppedIndices()
            build(indexMap.values.filter { indexState.find(it.def.name) != true })
        }
    }

    private fun clearDroppedIndices() {
        val dropped = indexState.keyIterator().filter { it !in indexMap }.toList()
        if (dropped.isEmpty()) return
        val cleared = dropped.filter { name ->
            try {
                delegate.dropUtilityMap(indexMapName(name))
                true
            } catch (e: Exception) {
                // its state kept, so the next open retries: failing the open would lock the app out of its data
                log.error("Failed to clear index $name, no longer passed; retrying on the next open", e)
                false
            }
        }
        if (cleared.isEmpty()) return
        // else a crash could keep the entries yet store their state gone
        commit()
        cleared.forEach { name -> indexState.put(name, null) }
        log.info("Cleared the entries of indices no longer passed: $cleared")
    }

    /**
     * Fills [indices] from every stored document; a document that fails to load, or whose keys fail, is logged and skipped.
     */
    private fun build(indices: List<IndexImpl<*, *, *>>) {
        if (indices.isEmpty()) return
        val start = System.currentTimeMillis()
        val names = indices.map { it.def.name }
        names.forEach { indexState.put(it, false) }
        // before the first entry: a crash mid-build must leave a state behind
        commit()
        indices.forEach { it.clear() }
        val builders = indices.map { it.Builder() }
        var count = 0
        for (id in delegate.getSequenceOfIDs()) {
            val document = try {
                delegate.find(id, Any::class)
            } catch (e: Exception) {
                log.error("Building indices $names: skipping document $id, which fails to load", e)
                null
            } ?: continue
            builders.forEach { it.add(id, document) }
            count++
        }
        builders.forEach { it.write() }
        // a stored `true` must imply every entry above
        commit()
        names.forEach { indexState.put(it, true) }
        log.info("Built indices $names over $count documents in ${System.currentTimeMillis() - start} ms")
    }

    override fun create(document: Any, id: UUID): Boolean {
        return indexUpdaterGlobalLock.withLock {
            val indexUpdates = prepareIndexUpdates(document, null)
            val created = delegate.create(document, id)
            if (created) {
                indexUpdates.forEach { it(id) }
            }
            created
        }
    }

    override fun create(document: Any): UUID {
        return indexUpdaterGlobalLock.withLock {
            val indexUpdates = prepareIndexUpdates(document, null)
            val id = delegate.create(document)
            indexUpdates.forEach { it(id) }
            id
        }
    }

    /**
     * Runs every index's [IndexDef.keyGetter] now, before the caller writes the document; the returned updates write the index
     * entries, given the document ID.
     */
    private fun prepareIndexUpdates(newDoc: Any?, oldDoc: Any?): List<(UUID) -> Unit> =
        indexMap.values.map { it.prepareUpdate(newDoc, oldDoc) }

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
     * Key a document by [String], a boxed primitive, [UUID], or a [CompositeKey] of those for a key of several parts: [MvstoreUMN]
     * stores them natively. It stores any other key class with Java serialization, which writes the class's full name and
     * `serialVersionUID` into the file: renaming, moving or obfuscating the class, or letting its `serialVersionUID` change, then makes
     * the index unreadable, and the UMN fails to open. Such a class must be `Serializable`, pin its `serialVersionUID` and never move.
     * See `D_index_key_codec`.
     *
     * @property K the type of keys. The type must properly implement [Any.equals] and [Any.hashCode]. Often the key is just a String,
     * but sometimes it's more complex comparable so that we can select a range of keys and have the values sorted.
     * @property D the type of documents indexed by this index. The indexing function is run on these documents as they are created or updated
     * within the [UMN].
     * @property V the type of values associated with the keys. Typically it's the same as [D]. It's the type of documents for which the
     * [valueIdGetter] provides IDs for.
     * @property name the unique name of the index
     * @property keyGetter computes keys for given document. If no indices are required for this document or if the document is not indexed
     * by this index, just return an empty set.
     * The function must run quick, must be pure and must be consistent in computing the same set of keys for a particular document.
     * It runs before the document is written: if it throws, the mutation throws and nothing is written.
     * @property documentType the type of documents indexed by this index. The [keyGetter] will only be called for documents of this type -
     * all other document types will be ignored and will not be indexed by this index.
     * @property valueType the type of documents associated with the keys. Typically equal to [documentType] for simple indices.
     * @property valueIdGetter which ID to actually associate with the key. Receives the document ID and the document itself. By default simply
     * returns the ID. Runs after the document is written, so it must not throw: the document would stay unindexed.
     */
    public class IndexDef<K : Comparable<K>, D : Any, V : Any>(
            public val name: String,
            public val keyGetter: (D) -> Set<K>,
            public val documentType: KClass<D>,
            public val valueType: KClass<V>,
            public val valueIdGetter: (UUID, D) -> UUID
    ) {
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

        public companion object {
            /**
             * Creates a simple index which simply maps keys to the ID of the document.
             * @param name the unique name of the index
             * @param keyGetter computes keys for given document. If no indices are required for this document or if the document is not indexed
             * by this index, just return an empty set.
             * The function must run quick, must be pure and must be consistent in computing the same set of keys for a particular document.
             * @property documentType the type of documents indexed by this index. The [keyGetter] will only be called for documents of this type -
             * all other document types will be ignored and will not be indexed by this index.
             */
            public fun <K : Comparable<K>, D: Any> simple(name: String, keyGetter: (D) -> Set<K>, documentType: KClass<D>) : IndexDef<K, D, D> =
                    IndexDef(name, keyGetter, documentType, documentType, { id, _ -> id })
        }
    }

    /**
     * @property K the type of keys (objects acting as index)
     * @property D the type of documents indexed by this index.
     * @property V the type of documents referenced by the keys.
     */
    public inner class IndexImpl<K : Comparable<K>, D: Any, V: Any>(public val def: IndexDef<K, D, V>) {

        /**
         * Maps key to a set of documents IDs (the [V] documents). Don't use Set since that's serialized using Java serialization; use Arrays
         * since that's serialized more effectively; see [ObjectDataType#TYPE_ARRAY] for more info.
         */
        private val keyType = CompositeKeyDataType()
        private val indexStore: UtilityMap<K, Array<UUID>> = delegate.openUtilityMap(indexMapName(def.name), keyType)

        internal fun clear() {
            indexStore.clear()
        }

        /**
         * Builds this index, cleared, over documents already stored: [add] each, then [write] once. Writing the entries as the walk
         * finds them, in document-ID order, would scatter them over the index, and every store MVStore makes on the way, one per
         * `autoCommitBufferSize` of changes, would rewrite nearly all its pages: a 320 MB file for 5 MB live in `IndexBuildGrowthTest`.
         */
        internal inner class Builder {
            /** Ordered as [indexStore]'s B-tree, by its own key type, so [write] fills each page once. */
            private val entries = TreeMap<K, MutableList<UUID>>(Comparator { a, b -> keyType.compare(a, b) })

            /**
             * Collects [document]'s entries; logs and skips it if its keys or value ID fail.
             */
            fun add(id: UUID, document: Any) {
                val docType = def.documentType.javaObjectType
                if (!docType.isInstance(document)) return
                try {
                    val d = docType.cast(document)
                    val keys = def.keyGetter(d)
                    val valueId = def.valueIdGetter(id, d)
                    keys.forEach { entries.getOrPut(it) { ArrayList(1) }.add(valueId) }
                } catch (e: Exception) {
                    log.error("Building index ${def.name}: skipping document $id, whose keys fail", e)
                }
            }

            /**
             * Writes the collected entries in key order: each key's value IDs in the order [add] met them, each once.
             */
            fun write() {
                for ((key, valueIds) in entries) indexStore.put(key, valueIds.distinct().toTypedArray())
                entries.clear()
            }
        }

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
         * Finds document matching lowest key. If there are multiple documents matching the lowest key, one is picked arbitrarily.
         * @param forKeys only consider documents mapped to these keys.
         */
        public fun findFirstDoc(forKeys: For<K>): Pair<UUID, V>? = getSequenceOfDocs(forKeys).firstOrNull()

        /**
         * Finds document ID matching lowest key. If there are multiple documents matching the lowest key, one is picked arbitrarily.
         * @param forKeys only consider documents mapped to these keys.
         */
        public fun findFirstID(forKeys: For<K>): UUID? = getSequenceOfIDs(forKeys).firstOrNull()

        /**
         * Finds all document IDs matching given keys. Returns an ordered set of IDs; documents with lower keys appearing sooner in the set.
         * @param forKeys only consider documents mapped to these keys.
         */
        public fun findAllIDs(forKeys: For<K>): Set<UUID> = getSequenceOfIDs(forKeys).toSet()

        /**
         * Finds all documents matching given keys. Returns an ordered map of IDs mapped to documents;
         * documents with lower keys appearing sooner in the set.
         * @param forKeys only consider documents mapped to these keys.
         */
        public fun findAllDocs(forKeys: For<K>): Map<UUID, V> = getSequenceOfDocs(forKeys).toMap()

        /**
         * Returns all documents matching given keys. The returned map is ordered - the keys are enumerated in increasing order.
         * @param page the offset and the limit. By default retrieves all matching documents.
         * @return a map of documents and their respective IDs. The map is ordered by the matching keys, ascending. May be empty.
         */
        public fun findAllDocsPaged(forKeys: For<K>, page: IntRange = 0..Int.MAX_VALUE): Map<UUID, V> {
            if (page.isEmpty() || forKeys.isEmpty) return mapOf()
            if (page == 0..Int.MAX_VALUE) return findAllDocs(forKeys)

            val result = mutableMapOf<UUID, V>()
            var currentIndex = 0
            getSequenceOfIDs(forKeys).forEach { id ->
                if (!result.containsKey(id) && delegate.exists(id)) {
                    if (currentIndex in page) {
                        val doc = delegate.find(id, def.valueType)
                        if (doc != null) result[id] = doc
                    }
                    currentIndex++
                    if (currentIndex > page.last) return result
                }
            }
            return result
        }

        /**
         * Returns all document IDs matching given keys. The returned map is ordered - the keys are enumerated in increasing order.
         * @param page the offset and the limit. By default retrieves all matching documents.
         * @return a set of document IDs. The set is ordered by the matching keys, ascending. May be empty.
         */
        public fun findAllIDsPaged(forKeys: For<K>, page: IntRange = 0..Int.MAX_VALUE): Set<UUID> {
            if (page.isEmpty() || forKeys.isEmpty) return setOf()
            if (page == 0..Int.MAX_VALUE) return findAllIDs(forKeys)

            val result = mutableSetOf<UUID>()
            var currentIndex = 0
            getSequenceOfIDs(forKeys).forEach { id ->
                if (!result.contains(id)) {
                    if (currentIndex in page) {
                        result.add(id)
                    }
                    currentIndex++
                    if (currentIndex > page.last) return result
                }
            }
            return result
        }

        /**
         * Counts all documents keyed with given keys.
         *
         * May count in non-existing documents if the documents were deleted during the run
         * of this function.
         * @return count of matching documents, 0 or greater.
         */
        public fun getCount(forKeys: For<K>): Int {
            // @todo mavi unique index size can simply be computed as indexStore.size()
            if (forKeys.isEmpty) return 0
            return when (forKeys) {
                is For.Key -> indexStore.find(forKeys.key)?.size ?: 0
                else -> getSequenceOfIDs(forKeys).distinct().count()
            }
        }

        public fun getSize(): Int = getCount(For.all())

        /**
         * Computes the keys of [newDoc] and [oldDoc] now; the returned function moves the document's index entries from the old
         * keys to the new ones. A create passes no [oldDoc], a delete no [newDoc].
         */
        internal fun prepareUpdate(newDoc: Any?, oldDoc: Any?): (id: UUID) -> Unit {
            val docType = def.documentType.javaObjectType
            val newD: D? = if (docType.isInstance(newDoc)) docType.cast(newDoc) else null
            val oldD: D? = if (docType.isInstance(oldDoc)) docType.cast(oldDoc) else null
            val newKeys: Set<K> = newD?.let(def.keyGetter) ?: setOf()
            val oldKeys: Set<K> = oldD?.let(def.keyGetter) ?: setOf()
            return { id ->
                val newValueId = newD?.let { def.valueIdGetter(id, it) }
                val oldValueId = oldD?.let { def.valueIdGetter(id, it) }
                if (newValueId != null && newValueId == oldValueId) {
                    registerUnregisterIndices(newValueId, newKeys, oldKeys)
                } else {
                    if (oldValueId != null) registerUnregisterIndices(oldValueId, setOf(), oldKeys)
                    if (newValueId != null) registerUnregisterIndices(newValueId, newKeys, setOf())
                }
            }
        }

        /**
         * Checks whether there are no documents in this index.
         */
        public fun isEmpty(): Boolean = indexStore.isEmpty()

        /**
         * Returns a lazily evaluated sequence of document IDs stored under given [forKeys]. The sequence will follow key ordering:
         * it will return documents mapped to lower keys sooner.
         *
         * This function is faster than [getSequenceOfDocs] since it doesn't load the documents.
         *
         * It is safe to modify the index (e.g. deleting documents) while iterating.
         * The sequence must not reflect further modifications; the sequence may thus return documents that were meanwhile deleted.
         *
         * Note: only IDs of indexed documents (documents for which the indexing function returned a non-null non-empty set) are returned.
         *
         * Note: the returned IDs may not be unique and may repeat in the sequence.
         * @param forKeys only consider documents mapped to these keys.
         */
        public fun getSequenceOfIDs(forKeys: For<K>): Sequence<UUID> {
            if (forKeys.isEmpty) return emptySequence()
            return when (forKeys) {
                is For.All -> indexStore.values().asSequence().flatMap { it.asSequence() }
                is For.Key -> indexStore.find(forKeys.key)?.asSequence() ?: emptySequence()
                is For.KeySet -> forKeys.set.sorted().asSequence().mapNotNull { key -> indexStore.find(key) } .flatMap { it.asSequence() }
                is For.KeyRange -> indexStore.keyIterator(forKeys.range).asSequence().mapNotNull { key -> indexStore.find(key) } .flatMap { it.asSequence() }
            }
        }

        /**
         * Returns a lazily evaluated sequence of documents and their IDs stored under given [forKeys]. The sequence will follow key ordering:
         * it will return documents mapped to lower keys sooner.
         *
         * This function is slower than [getSequenceOfIDs] since it doesn't load the documents.
         *
         * It is safe to modify the index (e.g. deleting documents) while iterating.
         * The sequence must not reflect further modifications; however the sequence will only return documents that are still available.
         *
         * Note: only IDs of indexed documents (documents for which the indexing function returned a non-null non-empty set) are returned.
         *
         * Note: the returned IDs may not be unique and may repeat in the sequence.
         * @param forKeys only consider documents mapped to these keys.
         */
        public fun getSequenceOfDocs(forKeys: For<K>): Sequence<Pair<UUID, V>> = getSequenceOfIDs(forKeys).mapNotNull { id ->
            val doc = delegate.find(id, def.valueType)
            if (doc == null) null else id to doc
        }

        /**
         * Finds all keys matching [keys].
         */
        public fun keySequence(keys: For<K>): Sequence<K> {
            if (keys.isEmpty) return emptySequence()
            return when (keys) {
                is For.All -> indexStore.keyIterator()
                is For.Key -> indexStore.keyIterator(keys.key..keys.key)
                is For.KeyRange -> indexStore.keyIterator(keys.range)
                is For.KeySet -> keys.set.asSequence().filter { indexStore.containsKey(it) }
            }
        }

        /**
         * Private since the sequence reflects modifications.
         */
        private fun getDescSequenceOfDocs(forKeys: For<K>): Sequence<Pair<UUID, V>> = getDescSequenceOfIDs(forKeys).mapNotNull { id ->
            val doc = delegate.find(id, def.valueType)
            if (doc == null) null else id to doc
        }

        /**
         * Private since the sequence reflects modifications.
         */
        private fun getDescSequenceOfAllDocIDs(maxKey: K? = indexStore.getLargestKey(), minKey: K? = null): Sequence<UUID> =
            generateSequence(maxKey) { id -> indexStore.lowerKey(id) }
                .takeWhile { key -> minKey == null || key >= minKey }
                .mapNotNull { key -> indexStore.find(key) }
                .flatMap { it.asSequence() }

        /**
         * Private since the sequence reflects modifications.
         */
        private fun getDescSequenceOfIDs(forKeys: For<K>): Sequence<UUID> {
            if (forKeys.isEmpty) return emptySequence()
            return when (forKeys) {
                is For.All -> getDescSequenceOfAllDocIDs()
                is For.Key -> indexStore.find(forKeys.key)?.asSequence() ?: emptySequence()
                is For.KeySet -> forKeys.set.sorted().asReversed().asSequence().mapNotNull { key -> indexStore.find(key) } .flatMap { it.asSequence() }
                is For.KeyRange -> getDescSequenceOfAllDocIDs(forKeys.range.endInclusive, forKeys.range.start)
            }
        }

        /**
         * Finds document matching lowest key. If there are multiple documents matching the lowest key, one is picked arbitrarily.
         * @param forKeys only consider documents mapped to these keys.
         */
        public fun findLastDoc(forKeys: For<K>): Pair<UUID, V>? = getDescSequenceOfDocs(forKeys).firstOrNull()

        /**
         * Finds document ID matching lowest key. If there are multiple documents matching the lowest key, one is picked arbitrarily.
         * @param forKeys only consider documents mapped to these keys.
         */
        public fun findLastID(forKeys: For<K>): UUID? = getDescSequenceOfIDs(forKeys).firstOrNull()

        /**
         * Checks whether the index contains *value* document with given [id].
         *
         * Highly inefficient operation! O(n)
         */
        public fun containsValueDocument(id: UUID): Boolean = getSequenceOfIDs(For.all()).contains(id)

        /**
         * The number of distinct keys, O(1); [getSize] counts the documents instead.
         */
        public fun keyCount(): Int = indexStore.keyCount()

        /**
         * The keys at positions [indices] in key order, O(log n + page size); position 0 is the smallest key, or the largest
         * when [descending]. A range past the last key returns what there is.
         * @throws IllegalArgumentException if [indices] holds a negative position.
         */
        public fun keyPage(indices: IntRange, descending: Boolean = false): List<K> {
            if (!descending) return indexStore.keyPage(indices)
            if (indices.isEmpty()) return listOf()
            require(indices.first >= 0) { "$indices contains negative indices" }
            val last = indexStore.keyCount() - 1
            return indexStore.keyPage((last - indices.last).coerceAtLeast(0)..last - indices.first).asReversed()
        }

        /**
         * A read-only list of this index's keys that loads them a page at a time, as it is read: walking it is fast, even
         * over a big index. Made for a UI list:
         *
         * ```
         * listView.adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1,
         *     index.keyPagedList(30, descending = true, dataMissingMarker = "Please refresh"))
         * ```
         *
         * Its size is fixed when it is created, so an adapter never sees it change unannounced: once the index changes, create
         * a new list and notify the adapter. Until then, a page loaded after the change shows the keys at their new positions,
         * and a position past the current last key reads as [dataMissingMarker].
         *
         * @param pageSize the keys loaded at once; for a UI, about as many as fit the screen. Keeps three pages around.
         * @param dataMissingMarker null: a position past the current last key throws [IndexOutOfBoundsException].
         */
        public fun keyPagedList(pageSize: Int, descending: Boolean = false, dataMissingMarker: K? = null): List<K> =
            PagedList(keyCount(), pageSize, dataMissingMarker) { keyPage(it, descending) }
    }

    override fun <V : Any> find(id: UUID, clazz: KClass<V>): V? = delegate.find(id, clazz)

    override fun exists(id: UUID): Boolean = delegate.exists(id)

    override fun delete(id: UUID) {
        indexUpdaterGlobalLock.withLock {
            val document: Any = find(id, Any::class) ?: return
            val indexUpdates = prepareIndexUpdates(null, document)
            delegate.delete(id)
            indexUpdates.forEach { it(id) }
        }
    }

    override fun update(id: UUID, document: Any) {
        indexUpdaterGlobalLock.withLock {
            val oldDoc: Any = get(id, Any::class)
            val indexUpdates = prepareIndexUpdates(document, oldDoc)
            delegate.update(id, document)
            indexUpdates.forEach { it(id) }
        }
    }

    override fun <K : Comparable<K>, V> openUtilityMap(name: String, keyType: DataType?, valueType: DataType?): UtilityMap<K, V> = delegate.openUtilityMap(name, keyType, valueType)

    override fun dropUtilityMap(name: String) {
        delegate.dropUtilityMap(name)
    }

    override fun close() {
        delegate.close()
    }

    /**
     * Returns a particular index with given [name], failing if no such index was provided to the constructor.
     */
    @Suppress("UNCHECKED_CAST")
    public fun <K : Comparable<K>, D: Any, V: Any> getIndex(name: String): IndexImpl<K, D, V> {
        return indexMap[name] as IndexImpl<K, D, V>? ?: throw IllegalStateException("Unknown index name $name, available indices: ${indexMap.keys}")
    }

    @Suppress("UNCHECKED_CAST")
    public fun <K : Comparable<K>, D: Any, V: Any> getIndex(index: IndexDef<K, D, V>): IndexImpl<K, D, V> {
        return indexMap[index.name] as IndexImpl<K, D, V>? ?: throw IllegalStateException("Unknown index $index, available indices: ${indexMap.keys}")
    }

    override val typeToClassResolver: TypeToClassResolver
        get() = delegate.typeToClassResolver

    override fun findSerialized(id: UUID): SerializedDocument? = delegate.findSerialized(id)

    override fun getSequenceOfIDs(): Sequence<UUID> = delegate.getSequenceOfIDs()

    // @todo implement unique index

    private companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(IndexingUMN::class.java)

        private fun indexMapName(indexName: String): String = "INDEX2_$indexName"
    }
}

/**
 * The key matching criteria. There are four criteria:
 * * [Key] - a single key
 * * [All] - all possible keys (the *universal set* of all possible keys)
 * * [KeySet] - a set of keys
 * * [KeyRange] - a range of keys
 * @param K the type of the key.
 */
public sealed class For<K: Comparable<K>> {
    public abstract val isEmpty: Boolean

    public data class KeyRange<K: Comparable<K>>(val range: ClosedRange<K>) : For<K>() {
        override val isEmpty: Boolean
            get() = range.isEmpty()
    }

    public data class KeySet<K: Comparable<K>>(val set: Set<K>) : For<K>() {
        public constructor(vararg set: K) : this(set.toSet())
        override val isEmpty: Boolean
            get() = set.isEmpty()
    }

    public data class Key<K: Comparable<K>>(val key: K) : For<K>() {
        override val isEmpty: Boolean
            get() = false
    }

    public companion object {
        public fun <K: Comparable<K>> all(): For<K> = All()

        /**
         * Every key that starts with [prefix]'s parts, [prefix] included:
         * `For.prefix(CompositeKey.of(categoryId))` matches `(categoryId, 1f)`, `(categoryId, 2.5f)` and so on.
         */
        public fun prefix(prefix: CompositeKey): For<CompositeKey> = KeyRange(prefix.prefixRange())
    }

    public class All<K: Comparable<K>>: For<K>() {
        override val isEmpty: Boolean
            get() = false
    }
}
