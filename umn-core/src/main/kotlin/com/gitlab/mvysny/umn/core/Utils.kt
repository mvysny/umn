package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVMap
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap

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
