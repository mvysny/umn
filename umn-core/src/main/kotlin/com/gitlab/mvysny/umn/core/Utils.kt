package com.gitlab.mvysny.umn.core

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [MutableMap.put]s the [value] into the map under given [key] or [MutableMap.remove]s it if it's null.
 */
fun <K, V> MutableMap<K, V>.putOrRemove(key: K, value: V?) {
    if (value == null) {
        remove(key)
    } else {
        put(key, value)
    }
}

/**
 * Provides a FIFO buffer abstraction while storing items into [UtilityMap].
 *
 * Thread-safe.
 */
internal class FIFOBuffer<T: Any>(private val log: UtilityMap<Long, T>) {
    private val readLock = ReentrantLock()
    private val writeLock = ReentrantLock()
    /**
     * Inserts the specified element at the end of this queue.
     */
    fun append(item: T) {
        writeLock.withLock {
            val id: Long = (log.getLargestKey() ?: 0L) + 1L
            log.put(id, item)
        }
    }
    /**
     * Retrieves and removes the head of this queue,
     * or returns `null` if this queue is empty.
     */
    fun poll(): T? = readLock.withLock {
        val lowestKey: Long = log.getSmallestKey() ?: return null
        val result: T? = log.find(lowestKey)
        log.put(lowestKey, null)
        result
    }

    /**
     * Retrieves (but does not remove) the head of this queue,
     * or returns `null` if this queue is empty.
     */
    fun peek(): T? = readLock.withLock {
        val lowestKey: Long = log.getSmallestKey() ?: return null
        log.find(lowestKey)
    }
}
