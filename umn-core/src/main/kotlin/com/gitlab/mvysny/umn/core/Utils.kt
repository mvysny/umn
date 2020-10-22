package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVMap
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.lang.ref.SoftReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/**
 * [MutableMap.put]s the [value] into the map under given [key] or [MutableMap.remove]s it if it's null. Thread-safe.
 */
public fun <K, V> MutableMap<K, V>.putOrRemove(key: K, value: V?) {
    if (value == null) {
        remove(key)
    } else {
        put(key, value)
    }
}

/**
 * Closes receiver quietly, logging any exceptions thrown by the [Closeable.close] method.
 */
public fun Closeable.closeQuietly() {
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
public fun <T: Closeable> T.tryInit(block: ()->Unit): T {
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

public fun serializeData(block: DataOutputStream.()->Unit): ByteArray =
    ByteArrayOutputStream().apply { DataOutputStream(this).block() } .toByteArray()


/**
 * Thread-safe. Updates the old value with a new one, but only when the old value is present.
 *
 * The default implementation may retry these steps when multiple threads
 * attempt updates including potentially calling the remapping function
 * multiple times.
 * @param remappingFunction may be called multiple times if multiple threads barge into this method.
 */
public fun <K, V> MVMap<K, V>.updateIfPresent(key:K, remappingFunction: (K, V)->V): V? = synchronized(this) {
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
public fun <K, V> ConcurrentHashMap<K, V>.updateIfPresent(key:K, remappingFunction: (K, V)->V): V? = synchronized(this) {
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
public object AutoTypeResolver : TypeToClassResolver {
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
 * Caches instances of [T] per-thread, into a [ThreadLocal] wrapped by [SoftReference]. Ideal for caching thread-unsafe
 * objects such as `SimpleDateFormat` etc.
 *
 * Example of use:
 * ```
 * val formatter: SimpleDateFormat by ThreadLocalCache { SimpleDateFormat("yyyy-MM-dd HH:mm:ss") }
 * ```
 */
public class ThreadLocalCache<T>(private val producer: ()->T) : ReadOnlyProperty<Nothing?, T> {
    private val tlc = ThreadLocal<SoftReference<T>>()
    override fun getValue(thisRef: Nothing?, property: KProperty<*>): T {
        var result: T? = tlc.get()?.get()
        if (result == null) {
            result = producer()
            tlc.set(SoftReference(result))
            return result
        }
        return result
    }
}
