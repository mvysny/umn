package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.UtilityMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Provides a FIFO buffer abstraction while storing items into [UtilityMap].
 *
 * Thread-safe, but multiple buffers can not operate over one map at the same time.
 */
public class AckFIFOBuffer<T: Any>(private val log: UtilityMap<Long, T>) {
    private var idToAddTo: Long = -1
    private var idOfItemToPoll: Long = -1
    private var idOfItemToAck: Long = -1
    private val lock = ReentrantLock()

    init {
        resetStatus()
    }

    /**
     * Inserts the specified element at the end of this queue.
     */
    public fun append(item: T) {
        lock.withLock {
            log.put(idToAddTo++, item)
        }
    }

    /**
     * Removes the smallest item from the [log]. If the log is empty, does nothing.
     */
    public fun ackLowest() {
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
    public fun poll(): T? {
        lock.withLock {
            if (idOfItemToPoll < idToAddTo) {
                return log.find(idOfItemToPoll++)!!
            } else {
                return null
            }
        }
    }

    /**
     * Makes [poll] hand out every unacked item again, oldest first. Call it when a new consumer
     * takes over: the previous one may have polled items it never delivered.
     */
    public fun rewindPoll() {
        lock.withLock {
            idOfItemToPoll = idOfItemToAck
        }
    }

    public fun clear() {
        lock.withLock {
            log.clear()
            resetStatus()
        }
    }

    private fun resetStatus() {
        idToAddTo = (log.getLargestKey() ?: -1L) + 1L
        idOfItemToPoll = log.getSmallestKey() ?: 0L
        idOfItemToAck = idOfItemToPoll
    }
}
