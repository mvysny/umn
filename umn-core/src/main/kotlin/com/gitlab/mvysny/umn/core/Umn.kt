package com.gitlab.mvysny.umn.core

import java.util.*
import kotlin.reflect.KClass

/**
 * A map-value NoSQL storage of documents, keyed by auto-generated UUIDs. There are the following implementations of the store:
 * * The [MvstoreUmn] H2 MVStore which offers unparalleled speed
 *
 * The basic functionality of this key store can be enhanced greatly by wrappers:
 * * The [IndexedUmn] which allows you to create indices over documents
 * * The [ReplayLogUmn] which captures changes in the storage as a replayable list of events which also provides synchronization capabilities.
 *
 * Generally the actual storage implementations (such as [MvstoreUmn]) define how exactly objects are serialized to byte arrays, or
 * deserialized back.
 *
 * The database is currently thread-unsafe.
 */
interface Umn {
    /**
     * Puts a new [document] into the database, returning a new generated unique ID for the document.
     */
    fun create(document: Any): UUID

    /**
     * Finds a document with given [id] and converts it to given [clazz]. If there is no such document or it has been deleted, returns null.
     */
    fun <V : Any> find(id: UUID, clazz: KClass<V>): V?

    /**
     * Checks whether a document with given [id] exists.
     */
    fun exists(id: UUID): Boolean

    /**
     * Retrieves a document with given [id], failing if no such document exists.
     * @throws IllegalArgumentException if no such document exists.
     */
    fun <V : Any> get(id: UUID, clazz: KClass<V>): V = find(id, clazz) ?: throw IllegalArgumentException("No document with id $id")

    /**
     * Overwrites a [document] with given [id]. The document must exist!
     */
    fun update(id: UUID, document: Any)

    /**
     * Deletes the document with given [id]. Does nothing if the document does not exist.
     */
    fun delete(id: UUID)

    /**
     * Opens an utility map, creating it if it doesn't exist. Various layers of document storage use the utility map to store various
     * stuff it needs:
     * * [IndexedUmn] creates a table which stores indices;
     * * [ReplayLogUmn] stores the log entries there
     *
     * This map is for internal storage purposes for this device only and must not be synced over network to other clients.
     * @param K the key type, must be one of the simple and easily serializable types: String, Long.
     */
    fun <K: Comparable<K>> openUtilityMap(name: String): UtilityMap<K>
}

/**
 * A very simple key-value store. An internal API, used by various implementations of [Umn] to store stuff there. Should not be used by
 * [Umn] clients.
 *
 * Currently not thread-safe.
 */
interface UtilityMap<K: Comparable<K>> {
    /**
     * Finds a value under given [key]; returns null if nothing is stored there.
     */
    fun find(key: K): ByteArray?

    /**
     * Puts [value] under given [key]; if the value is null the mapping is removed.
     */
    fun put(key: K, value: ByteArray?)

    /**
     * Returns the smallest key currently stored in the map.
     */
    fun getSmallestKey(): K?

    /**
     * Returns the largest key currently stored in the map.
     */
    fun getLargestKey(): K?
}
