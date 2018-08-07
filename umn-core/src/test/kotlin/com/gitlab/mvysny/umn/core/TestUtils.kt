package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVStore
import kotlin.reflect.KClass

/**
 * Creates an [UMN] backed by a file-based MV Store.
 */
fun createMvstoreUMN(): MvstoreUMN = MvstoreUMN(MVStore.open(createTempFile().absolutePath), JavaDocumentSerializer,
    AutoTypeResolver, { id -> throw RuntimeException("We're starting with an empty database!") })

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
