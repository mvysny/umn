package com.gitlab.mvysny.umn.core

import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * A map-value NoSQL storage of documents, keyed by auto-generated UUIDs. There are the following implementations of the store:
 * * The [MvstoreUmn] H2 MVStore which offers unparalleled speed
 *
 * The basic functionality of this key store can be enhanced greatly by wrappers:
 * * The [IndexingUmn] which allows you to create indices over documents
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
}

/**
 * A very simple in-memory storage of objects. Ideal for writing tests. Thread-safe.
 */
class InMemoryUmn : Umn {
    private val map = ConcurrentHashMap<UUID, Any>()

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
}
