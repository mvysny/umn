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
     *
     * The [clazz] parameter is necessary for, say, JSON converters which do not know the exact class
     * of the expected document.
     */
    fun <V : Any> find(id: UUID, clazz: KClass<V>): V?

    /**
     * Checks whether a document with given [id] exists.
     */
    fun exists(id: UUID): Boolean

    /**
     * Retrieves a document with given [id], failing if no such document exists.
     * @throws IllegalArgumentException if no document with given ID exists.
     */
    fun <V : Any> get(id: UUID, clazz: KClass<V>): V = find(id, clazz) ?: throw IllegalArgumentException("No document with id $id")

    /**
     * Overwrites a [document] with given [id]. The document must exist!
     * @param clazz needed by the indexing UMN to retrieve the old copy of the document to remove its indices.
     * @throws IllegalArgumentException if no document with given ID exists.
     */
    fun <V : Any> update(id: UUID, document: V, clazz: KClass<V>)

    /**
     * Deletes the document with given [id]. Does nothing if the document does not exist.
     * @param clazz this is used for e.g. [IndexingUMN] when the document must be loaded before deleting, so that its indices will be deleted
     * properly.
     */
    fun delete(id: UUID, clazz: KClass<out Any>)

    /**
     * Opens an utility map, creating it if it doesn't exist. Various layers of document storage use the utility map to store various
     * stuff it needs:
     * * [IndexedUmn] creates a table which stores indices;
     * * [ReplayLogUmn] stores the log entries there
     *
     * This map is for internal storage purposes for this device only and must not be synced over network to other clients.
     * @param K the key type, must be one of the simple and easily serializable types: String, Long.
     */
    fun <K: Comparable<K>, V> openUtilityMap(name: String): UtilityMap<K, V>

    /**
     * INTERNAL USE ONLY!!! Puts a [document] with given [id] to the database.
     * Does nothing if there already is a document with given ID.
     *
     * Never use! Only used by [GlobalLogPuller] to apply changes. This function breaks the guarantee that a document
     * delete with given id is permanent and irreversible. @todo mavi what may this cause?
     * @return true if the document was non-existent and was created, false if it already existed and nothing was done
     */
    fun create(document: Any, id: UUID): Boolean

    val delegate: UMN?
}

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
     * Returns an iterator which walks over keys in given range in ascending order.
     *
     * The iterator must not throw [ConcurrentModificationException]
     * and must not be affected by any further modifications to the map.
     */
    fun keyIterator(range: ClosedRange<K>): Iterator<K>

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
}
