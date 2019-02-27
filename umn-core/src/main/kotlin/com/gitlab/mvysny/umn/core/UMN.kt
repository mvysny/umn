package com.gitlab.mvysny.umn.core

import java.io.Closeable
import java.util.*
import kotlin.reflect.KClass

/**
 * A map-value NoSQL storage of documents, keyed by auto-generated UUIDs. There are the following implementations of the store:
 * * The [MvstoreUMN] H2 MVStore which offers unparalleled speed
 *
 * The basic functionality of this key store can be enhanced greatly by wrappers:
 * * The [IndexingUMN] which allows you to create indices over documents
 * * The [AnnouncingUMN] which captures changes in the storage as a replayable list of events which also provides synchronization capabilities.
 *
 * Generally the actual storage implementations (such as [MvstoreUMN]) define how exactly objects are serialized to byte arrays, or
 * deserialized back.
 *
 * Thread safety: to be decided, currently some implementors may be thread-unsafe.
 */
interface UMN : Closeable {
    /**
     * Puts a new [document] into the database, returning a new generated unique ID for the document.
     */
    fun create(document: Any): UUID

    /**
     * Finds a document with given [id] and converts it to given [clazz]. If there is no such document or it has been deleted, returns null.
     * @param clazz only used for casting purposes - the actual document type is stored in the database.
     */
    fun <V : Any> find(id: UUID, clazz: KClass<V>): V?

    /**
     * Low-level API, don't use. Finds a document with given [id] and returns its serialized form. If there is no such document or it has been deleted, returns null.
     */
    fun findSerialized(id: UUID): SerializedDocument?

    /**
     * Checks whether a document with given [id] exists.
     */
    fun exists(id: UUID): Boolean

    /**
     * Retrieves a document with given [id], failing if no such document exists.
     * @param clazz only used for casting purposes - the actual document type is stored in the database.
     * @throws IllegalArgumentException if no document with given ID exists.
     */
    fun <V : Any> get(id: UUID, clazz: KClass<V>): V = find(id, clazz) ?: throw IllegalArgumentException("No document with id $id")

    /**
     * Overwrites a [document] with given [id]. The document must exist!
     * @throws IllegalArgumentException if no document with given ID exists.
     */
    fun update(id: UUID, document: Any)

    /**
     * Deletes the document with given [id]. Does nothing if the document does not exist.
     */
    fun delete(id: UUID)

    /**
     * Opens an utility map, creating it if it doesn't exist. Various layers of document storage use the utility map to store various
     * stuff it needs:
     * * [IndexingUMN] creates a table which stores indices;
     * * [ReplayLogUmn] stores the log entries there
     *
     * This map is for internal storage purposes for this device only and must not be synced over network to other clients.
     * @param K the key type, must be one of the simple and easily serializable types: String, Long.
     */
    fun <K: Comparable<K>, V> openUtilityMap(name: String): UtilityMap<K, V>

    /**
     * INTERNAL USE ONLY. Puts a [document] with given [id] to the database.
     * Does nothing if there already is a document with given ID.
     *
     * Never use directly! Only used by [com.gitlab.mvysny.umn.sync.SyncingUMN] to apply changes. This function breaks the guarantee that a document
     * delete with given id is permanent and irreversible. @todo mavi why is this guarantee important and what would breaking of this cause?
     * @return true if the document was non-existent and was created, false if it already existed and nothing was done
     */
    fun create(document: Any, id: UUID): Boolean

    /**
     * UMN employs the delegate pattern, e.g. [IndexingUMN] allows you to have document indices but it uses delegate [UMN]
     * to store those documents. Having delegate in the API allows you to find particular UMN wrapper in the chain of delegates.
     */
    val delegate: UMN?

    /**
     * Resolves [DocType] to Class so that [find]/[get] can deserialize document properly from the storage. Also used by [IndexingUMN.delete]
     * since it needs to load the document first, to properly remove indices.
     */
    val typeToClassResolver: TypeToClassResolver

    /**
     * Returns a lazily evaluated sequence of all document IDs. No specific order is imposed.
     *
     * This function is faster than [getSequenceOfSerializedDocs] since it doesn't load the documents.
     *
     * It is safe to modify the UMN database (e.g. deleting documents) while iterating.
     * The sequence must not reflect further modifications; the sequence may thus return IDs of documents that were meanwhile deleted.
     */
    fun getSequenceOfIDs(): Sequence<UUID>

    /**
     * Returns a lazily evaluated sequence of documents and their IDs present in the UMN. The documents are returned in no specific order.
     *
     * This function is slower than [getSequenceOfIDs] since it doesn't load the documents.
     *
     * It is safe to modify the UMN (e.g. deleting documents) while iterating.
     * The sequence must not reflect further modifications; however the sequence will only return documents that are still available.
     * @return a sequence of pairs; [Pair.first] is the document ID, [Pair.second] is the serialized document.
     */
    fun getSequenceOfSerializedDocs(): Sequence<Pair<UUID, SerializedDocument>> = getSequenceOfIDs().mapNotNull { id ->
        val doc = findSerialized(id)
        if (doc == null) null else id to doc
    }

    /**
     * If true, the UMN is empty (there are no documents).
     */
    fun isEmpty(): Boolean = getSequenceOfIDs().none()
}

/**
 * Every document stored has a type associated. This resolver is able to provide a [KClass] for document type,
 * so that we can load and deserialize the document properly.
 */
interface TypeToClassResolver {
    fun toClass(type: DocType): KClass<*>
    fun toType(clazz: KClass<*>): DocType
}

/**
 * The type of the document. [TypeToClassResolver] is used, to resolve type to class, which then is used by [DocumentSerializer]
 * to serialize/deserialize documents when they are sent to the global log. Every document must have a type.
 */
typealias DocType = Short

/**
 * The serialized document itself. First two bytes are [DocType]; remaining bytes is the
 * serialized document itself, saved via the [DocumentSerializer].
 */
typealias SerializedDocument = ByteArray

fun <T: UMN> umnfacet(this_: UMN, clazz: KClass<T>): T {
    if (clazz.java.isInstance(this_)) {
        return clazz.java.cast(this_)
    }
    val d = this_.delegate ?: throw IllegalArgumentException("This UMN chain contains no instance of $clazz")
    return umnfacet(d, clazz)
}

inline fun <reified T: UMN> UMN.facet(): T = umnfacet(this, T::class)

/**
 * A very simple key-value store. An internal API, used by various implementations of [UMN] to store stuff there. Should not be used by
 * [UMN] clients.
 *
 * Thread safety: to be decided, currently some implementors may be thread-unsafe.
 * @param K the type of the key, must be comparable so that the ranges can be looked up conveniently. Only a certain types of keys may
 * be supported by the underlying UMN storage type, check the storage documentation for details. Yet, at least [String] and [Long] must always
 * be supported.
 * @param V the value. Only a certain types of values may
 * be supported by the underlying UMN storage type, check the storage documentation for details. Yet, at least [String], [UUID], primitives
 * and sets/lists of primitives must always be supported.
 */
interface UtilityMap<K: Comparable<K>, V> {
    /**
     * Finds a value under given [key]; returns null if nothing is stored there.
     */
    fun find(key: K): V?

    /**
     * Puts [value] under given [key]; if the value is null the mapping is removed.
     */
    fun put(key: K, value: V?)

    /**
     * Returns the smallest key currently stored in the map. Returns null if the map is empty.
     */
    fun getSmallestKey(): K?

    /**
     * Returns the largest key currently stored in the map. Returns null if the map is empty.
     */
    fun getLargestKey(): K?

    /**
     * Returns an iterator which walks over keys in given [range] in ascending order.
     *
     * The iterator must not throw [ConcurrentModificationException]
     * and must not be affected by any further modifications to the map.
     * @param range if null, all keys are enumerated.
     */
    fun keyIterator(range: ClosedRange<K>? = null): Sequence<K>

    fun containsKey(key: K): Boolean

    /**
     * Iterates over all values present in this map. The iteration follows the key order, ascending.
     *
     * The iterator must not throw [ConcurrentModificationException]
     * and must not be affected by any further modifications to the map.
     */
    fun values(): Iterator<V>

    /**
     * Clears the map and removes all keys and values.
     */
    fun clear()

    /**
     * Checks if there are no entries in the map.
     */
    fun isEmpty(): Boolean

    /**
     * Get the largest key that is smaller than the given [key], or null if no
     * such key exists.
     */
    fun lowerKey(key: K): K?

    /**
     * Returns the number of keys in this map.
     */
    fun keyCount(): Int

    /**
     * Returns keys in given index range. O(log([keyCount]) + indexRange.size).
     * The range may exceed [keyCount], in such case fewer keys are returned.
     * @throws IllegalArgumentException if range includes negative indices.
     */
    fun keyPage(indexRange: IntRange): List<K>
}
