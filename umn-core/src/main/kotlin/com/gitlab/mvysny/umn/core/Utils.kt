package com.gitlab.mvysny.umn.core

import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream

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
