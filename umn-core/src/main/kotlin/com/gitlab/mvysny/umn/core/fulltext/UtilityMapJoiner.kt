package com.gitlab.mvysny.umn.core.fulltext

import com.gitlab.mvysny.umn.core.UtilityMap
import java.util.*

/**
 * Returns a sequence of keys present in all of given maps. The sequence returns the keys in ascending order.
 */
fun Iterable<UtilityMap<UUID, *>>.join(): Sequence<UUID> {
    val smallestFirst: List<UtilityMap<UUID, *>> = sortedBy { it.keyCount() }
    val smallest: UtilityMap<UUID, *> = smallestFirst.firstOrNull() ?: return emptySequence()
    if (smallest.isEmpty()) {
        return emptySequence()
    }
    if (smallestFirst.size == 1) {
        return smallest.keyIterator()
    }
    if (smallest.keyCount() <= 10) {
        return hashJoin(smallestFirst)
    }
    return mergeJoin(smallestFirst.map { it.keyIterator().iterator() })
}

private fun hashJoin(smallestFirst: List<UtilityMap<UUID, *>>): Sequence<UUID> {
    val smallest: UtilityMap<UUID, *> = smallestFirst.first()
    val rest = smallestFirst.subList(1, smallestFirst.size)
    return smallest.keyIterator().filter { id -> rest.all { it.containsKey(id) } }
}

internal fun <T> mergeJoin(iterators: List<Iterator<T>>): Sequence<T> where T: Comparable<T>, T: Any {
    val iterators: List<IteratorWrapper<T>> = iterators.map { IteratorWrapper(it) }
    return sequence {
        val firstIterator = iterators[0]
        while (true) {
            // run one cycle through all iterators; try to find a common item (a barrier).
            var barrier: T = firstIterator.current ?: return@sequence
            var barrierHit = true

            // bump every other iterator and try to reach the common barrier
            for (i in 1 until iterators.size) {
                val next: T = iterators[i].skipUntil(barrier) ?: return@sequence
                if (next != barrier) {
                    // this iterator doesn't contain the 'barrier' item, cannot hit barrier. Still, bump other
                    // iterators
                    barrierHit = false
                    barrier = next
                }
            }

            // bump first iterator to the largest item found. No point in trying lower items since they're not present
            // in other iterators.
            firstIterator.skipUntil(barrier) ?: return@sequence

            if (barrierHit) {
                // we have found a common item. return it.
                yield(barrier)
                if (!firstIterator.hasNext()) {
                    return@sequence
                } else {
                    firstIterator.next()
                }
            }
        }
    }
}

private class IteratorWrapper<T>(val iterator: Iterator<T>) : Iterator<T> where T : Comparable<T>, T : Any {
    /**
     * Last item returned by [next], or null if [iterator] is empty or [skipUntil] has hit the end.
     */
    var current: T? = null
        private set

    init {
        if (!iterator.hasNext()) {
            current = null
        } else {
            current = iterator.next()
        }
    }

    override fun hasNext(): Boolean = current != null && iterator.hasNext()

    override fun next(): T {
        current = iterator.next()
        return current!!
    }

    /**
     * Skips items until [item] is encountered, or an higher item is encountered, or an end of stream is reached.
     * If [item] is found, [current] will be equal to [item].
     * @return item found; the value of [current]. Will be equal to [item] if item is found. `null` if an end of stream is reached.
     */
    fun skipUntil(item: T): T? {
        // @todo mavi this could be optimized if MVStore Cursor will offer such functionality.
        while (current != null && current!! < item) {
            if (!hasNext()) {
                current = null;
                return null
            }
            next()
        }
        return current
    }
}
