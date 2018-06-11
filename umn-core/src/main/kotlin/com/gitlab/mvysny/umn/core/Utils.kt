package com.gitlab.mvysny.umn.core

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
