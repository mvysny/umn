package com.gitlab.mvysny.umn.core.fulltext

import com.gitlab.mvysny.umn.core.UMN
import com.gitlab.mvysny.umn.core.UtilityMap
import org.h2.mvstore.WriteBuffer
import org.h2.mvstore.type.DataType
import org.h2.mvstore.type.StringDataType
import java.nio.ByteBuffer
import java.util.*

/**
 * Not thread-safe.
 * @param tokens produces tokens for a document, see [Analyzer.getTokens] for more info. If the closure returns an empty
 * set, that document is then ignored.
 */
class FullTextUMN(override val delegate: UMN, private val tokens: (Any) -> Set<String>) : UMN by delegate {
    private fun getTokenMap(token: String): UtilityMap<MyKey, Boolean> = delegate.openUtilityMap("_FT_$token", MyKey.Type(), StringDataType.INSTANCE)
    override fun create(document: Any): UUID {
        val id = delegate.create(document)
        tokens(document).forEach { token -> getTokenMap(token).put(key(document, id), true) }
        return id
    }

    override fun update(id: UUID, document: Any) {
        val oldDoc: Any = get(id, Any::class)
        val oldTokens = tokens(oldDoc)
        val newTokens = tokens(document)
        delegate.update(id, document)
        (oldTokens - newTokens).forEach { token -> getTokenMap(token).put(key(document, id), null) }
        (newTokens - oldTokens).forEach { token -> getTokenMap(token).put(key(document, id), true) }
    }

    override fun delete(id: UUID) {
        val oldDoc: Any = get(id, Any::class)
        val oldTokens = tokens(oldDoc)
        delegate.delete(id)
        oldTokens.forEach { token -> getTokenMap(token).put(key(oldDoc, id), null) }
    }

    override fun create(document: Any, id: UUID): Boolean {
        if (delegate.create(document, id)) {
            tokens(document).forEach { token -> getTokenMap(token).put(key(document, id), true) }
            return true
        }
        return false
    }

    fun search(tokens: Set<String>): Sequence<UUID> {
        val maps: List<UtilityMap<MyKey, Boolean>> = tokens.map { token -> getTokenMap(token) }
        return maps.join().map { it.id }
    }
}

data class MyKey(val importance: Int, val id: UUID) : Comparable<MyKey> {
    override fun compareTo(other: MyKey): Int {
        var result = importance.compareTo(other.importance)
        if (result == 0) {
            result = id.compareTo(other.id)
        }
        return result
    }

    class Type : DataType {
        override fun write(buff: WriteBuffer, obj: Any) {
            val key = obj as MyKey
            buff.putInt(key.importance)
            buff.putLong(key.id.mostSignificantBits)
            buff.putLong(key.id.leastSignificantBits)
        }

        override fun write(buff: WriteBuffer, obj: Array<out Any>, len: Int, key: Boolean) {
            for (i in 0 until len) {
                write(buff, obj[i])
            }
        }

        override fun compare(a: Any, b: Any): Int = (a as MyKey).compareTo(b as MyKey)

        override fun getMemory(obj: Any?): Int = 40 + 4 + 24

        override fun read(buff: ByteBuffer): Any {
            val importance = buff.int
            val msb = buff.long
            val lsb = buff.long
            return MyKey(importance, UUID(msb, lsb))
        }

        override fun read(buff: ByteBuffer, obj: Array<Any>, len: Int, key: Boolean) {
            for (i in 0 until len) {
                obj[i] = read(buff)
            }
        }
    }
}

fun key(document: Any, id: UUID) = MyKey((document as String).length, id)
