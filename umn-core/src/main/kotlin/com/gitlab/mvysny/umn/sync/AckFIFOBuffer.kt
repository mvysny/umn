package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.UtilityMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Provides a FIFO buffer abstraction while storing items into [UtilityMap].
 *
 * Thread-safe, but multiple buffers can not operate over one map at the same time.
 */
class AckFIFOBuffer<T: Any>(private val log: UtilityMap<Long, T>) {
    private var idToAddTo = (log.getLargestKey() ?: -1L) + 1L
    private var idOfItemToPoll = log.getSmallestKey() ?: 0L
    private var idOfItemToAck = idOfItemToPoll
    private val lock = ReentrantLock()

    /**
     * Inserts the specified element at the end of this queue.
     */
    fun append(item: T) {
        lock.withLock {
            log.put(idToAddTo++, item)
        }
    }

    /**
     * Removes the smallest item from the [log]. If the log is empty, does nothing.
     */
    fun ackLowest() {
        lock.withLock {
            if (idOfItemToAck < idToAddTo) {
                log.put(idOfItemToAck++, null)
            }
        }
    }

    /**
     * Retrieves and removes the head of this queue,
     * or returns `null` if this queue is empty.
     *
     * However, the item does not really disappear from the [log] unless it is acked by [ackLowest].
     */
    fun poll(): T? {
        lock.withLock {
            if (idOfItemToPoll < idToAddTo) {
                return log.find(idOfItemToPoll++)!!
            } else {
                return null
            }
        }
    }
}
