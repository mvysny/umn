package com.gitlab.mvysny.umn.core.fulltext

import com.gitlab.mvysny.umn.core.*
import org.h2.mvstore.MVStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.IllegalArgumentException
import kotlin.reflect.KClass
import java.nio.file.Files

object FastTypeResolver : TypeToClassResolver {
    override fun toClass(type: DocType): KClass<*> {
        require(type == 0.toShort())
        return String::class
    }

    override fun toType(clazz: KClass<*>): DocType = when(clazz) {
        String::class -> 0
        else -> throw IllegalArgumentException("$clazz not supported")
    }
}

object FastSerializer : DocumentSerializer {
    override fun serialize(document: Any, dout: DataOutputStream) {
        val str = document as String
        dout.writeUTF(str)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> deserialize(clazz: KClass<T>, din: DataInputStream): T {
        return din.readUTF() as T
    }
}

fun createMvstoreUMN(dbFileName: String = createTempFile().absolutePath): MvstoreUMN {
    val mvStore: MVStore = MVStore.Builder().compress().fileName(dbFileName).open()
    return MvstoreUMN(mvStore, FastSerializer,
            FastTypeResolver)
}

fun time(what: String, block: ()->Unit) {
    val start = System.currentTimeMillis()
    block()
    println("$what took ${System.currentTimeMillis() - start}ms")
}

fun File.calculateDirSize(): Long = Files.walk(toPath()).mapToLong { p -> p.toFile().length() }.sum()
