package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVMap
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * [MutableMap.put]s the [value] into the map under given [key] or [MutableMap.remove]s it if it's null. Thread-safe.
 */
fun <K, V> MutableMap<K, V>.putOrRemove(key: K, value: V?) {
    if (value == null) {
        remove(key)
    } else {
        put(key, value)
    }
}

/**
 * Closes receiver quietly, logging any exceptions thrown by the [Closeable.close] method.
 */
fun Closeable.closeQuietly() {
    try {
        close()
    } catch (t: Exception) {
        LoggerFactory.getLogger(javaClass).warn("Failed to close $this", t)
    }
}

/**
 * Intended to be used with a constructor which initializes multiple [Closeable]s.
 * The [Closeable] is closed if [block] fails. If the block succeeds, the [Closeable]
 * remains opened.
 */
fun <T: Closeable> T.tryInit(block: ()->Unit): T {
    try {
        block()
    } catch (t: Throwable) {
        try {
            close()
        } catch (ex: Throwable) {
            t.addSuppressed(ex)
        } finally {
            throw t
        }
    }
    return this
}

fun serializeData(block: DataOutputStream.()->Unit): ByteArray =
    ByteArrayOutputStream().apply { DataOutputStream(this).block() } .toByteArray()


/**
 * Thread-safe. Updates the old value with a new one, but only when the old value is present.
 *
 * The default implementation may retry these steps when multiple threads
 * attempt updates including potentially calling the remapping function
 * multiple times.
 * @param remappingFunction may be called multiple times if multiple threads barge into this method.
 */
fun <K, V> MVMap<K, V>.updateIfPresent(key:K, remappingFunction: (K, V)->V): V? = synchronized(this) {
    // we can't use ConcurrentMap.computeIfPresent() since that's Java 8/Android 24 and higher :(

    // use synchronized() - the remappingFunction may potentially be slow (since it serializes documents), so
    // it's generally better to obtain a lock (since that's way faster on Android's lowend CPU hardware)
    var oldValue = get(key)
    while (oldValue != null) {
        val newValue = remappingFunction(key, oldValue)
        if (newValue != null) {
            if (replace(key, oldValue, newValue)) {
                return newValue
            }
        } else if (remove(key, oldValue)) {
            return null;
        }
        oldValue = get(key)
    }
    return null
}

/**
 * Thread-safe. Updates the old value with a new one, but only when the old value is present.
 *
 * The default implementation may retry these steps when multiple threads
 * attempt updates including potentially calling the remapping function
 * multiple times.
 * @param remappingFunction may be called multiple times if multiple threads barge into this method.
 */
fun <K, V> ConcurrentHashMap<K, V>.updateIfPresent(key:K, remappingFunction: (K, V)->V): V? = synchronized(this) {
    // we can't use ConcurrentHashMap.computeIfPresent() since that's Java 8/Android 24 and higher :(

    // use synchronized() - the remappingFunction may potentially be slow (since it serializes documents), so
    // it's generally better to obtain a lock (since that's way faster on Android's lowend CPU hardware)
    var oldValue = get(key)
    while (oldValue != null) {
        val newValue = remappingFunction(key, oldValue)
        if (newValue != null) {
            if (replace(key, oldValue, newValue)) {
                return newValue
            }
        } else if (remove(key, oldValue)) {
            return null;
        }
        oldValue = get(key)
    }
    return null
}

/**
 * Dynamically assigns type to a class. Not persistent - the numbering may change as the JVM restarts.
 * **Don't use for production!** Only use for evaluation or testing purposes.
 */
object AutoTypeResolver : TypeToClassResolver {
    private val toClass = mutableMapOf<Short, KClass<*>>()
    private val toType = mutableMapOf<KClass<*>, Short>()
    private var nextType = 0.toShort()

    override fun toClass(type: Short): KClass<*> = requireNotNull(toClass[type]) { "type $type has not yet been registered" }

    override fun toType(clazz: KClass<*>): Short {
        var type = toType[clazz]
        if (type == null) {
            type = nextType++
            toClass[type] = clazz
            toType[clazz] = type
        }
        return type
    }
}








/**
 * Returns a range that is an intersection of this range and [other].
 */
fun IntRange.intersection(other: IntRange): IntRange {
    if (this.start > other.endInclusive || this.endInclusive < other.start) {
        return IntRange.EMPTY
    }
    if (contains(other)) {
        return other
    }
    if (other.contains(this)) {
        return this
    }
    val s = this.start.coerceAtLeast(other.start)
    val e = this.endInclusive.coerceAtMost(other.endInclusive)
    assert(s <= e)
    return s..e
}

/**
 * Checks whether this range fully contains the [other] range.
 */
operator fun <T: Comparable<T>> ClosedRange<T>.contains(other: ClosedRange<T>) =
        other.isEmpty() || (start <= other.start && endInclusive >= other.endInclusive)

/**
 * Returns a range that is an intersection of this range and [other].
 */
fun LongRange.intersection(other: LongRange): LongRange {
    if (this.start > other.endInclusive || this.endInclusive < other.start) {
        return LongRange.EMPTY
    }
    if (contains(other)) {
        return other
    }
    if (other.contains(this)) {
        return this
    }
    val s = this.start.coerceAtLeast(other.start)
    val e = this.endInclusive.coerceAtMost(other.endInclusive)
    assert(s <= e)
    return s..e
}

val ClosedRange<Int>.longRange: LongRange get() = start.toLong()..endInclusive.toLong()

val ClosedRange<Long>.intRange: IntRange get() = start.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()..
        endInclusive.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

/**
 * Returns a view of the portion of this list between the specified [indexRange].
 * The returned list is backed by this list, so non-structural changes in the returned list are reflected in this list, and vice-versa.
 *
 * Structural changes in the base list make the behavior of the view undefined.
 */
fun <T> List<T>.subList(indexRange: IntRange): List<T> {
    if (indexRange.isEmpty()) {
        val index = indexRange.start.coerceIn(0..size)
        return subList(index, index)
    }
    return subList(indexRange.start, if (indexRange.endInclusive == Int.MAX_VALUE) Int.MAX_VALUE else indexRange.endInclusive + 1)
}
