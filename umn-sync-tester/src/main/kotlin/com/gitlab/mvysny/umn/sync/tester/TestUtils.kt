package com.gitlab.mvysny.umn.sync.tester

import com.gitlab.mvysny.umn.core.TypeToClassResolver
import kotlin.reflect.KClass
import kotlin.test.expect

/**
 * Expects that a value provided by the [actual] closure eventually becomes as [expected].
 * Wait at most [maxDelay].
 */
fun <T> expectAsync(expected: T, maxDelay: Long = 3000L, actual: ()->T) {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start <= maxDelay) {
        if (expected == actual()) return
        Thread.sleep(10)
    }
    expect(expected, actual)
}

/**
 * Dynamically assigns type to a class. Don't use for production!
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
