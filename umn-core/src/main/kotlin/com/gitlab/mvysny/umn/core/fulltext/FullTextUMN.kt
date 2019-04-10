package com.gitlab.mvysny.umn.core.fulltext

import com.gitlab.mvysny.umn.core.UMN
import com.gitlab.mvysny.umn.core.UtilityMap
import org.h2.mvstore.DataUtils
import org.h2.mvstore.WriteBuffer
import org.h2.mvstore.type.DataType
import java.nio.ByteBuffer
import java.util.*

/**
 * EXPERIMENTAL, UNDER DEVELOPMENT, DON'T USE.
 * Not thread-safe.
 * @param tokens produces tokens for a document, see [Analyzer.getTokens] for more info. If the closure returns an empty
 * set, that document is then ignored.
 */
class FullTextUMN(override val delegate: UMN, private val tokens: (Any) -> Set<String>) : UMN by delegate {
    private val tokensIndex = delegate.openUtilityMap<String, Int>("FT___tokens")
    private fun tokenToInt(token: String): Int {
        var index = tokensIndex.find(token)
        if (index == null) {
            index = tokensIndex.keyCount()
            tokensIndex.put(token, index)
        }
        return index
    }
    private val tokenMap = delegate.openUtilityMap<MyKey, Boolean>("_FT___tokenmap", MyKey.Type(), BooleanType())

    private fun getTokenMap(token: String): UtilityMap<MyKey, Boolean> = tokenMap
    override fun create(document: Any): UUID {
        val id = delegate.create(document)
        tokens(document).forEach { token -> getTokenMap(token).put(key(document, token, id), true) }
        return id
    }

    override fun update(id: UUID, document: Any) {
        val oldDoc: Any = get(id, Any::class)
        val oldTokens = tokens(oldDoc)
        val newTokens = tokens(document)
        delegate.update(id, document)
        (oldTokens - newTokens).forEach { token -> getTokenMap(token).put(key(document, token, id), null) }
        (newTokens - oldTokens).forEach { token -> getTokenMap(token).put(key(document, token, id), true) }
    }

    override fun delete(id: UUID) {
        val oldDoc: Any = get(id, Any::class)
        val oldTokens = tokens(oldDoc)
        delegate.delete(id)
        oldTokens.forEach { token -> getTokenMap(token).put(key(oldDoc, token, id), null) }
    }

    override fun create(document: Any, id: UUID): Boolean {
        if (delegate.create(document, id)) {
            tokens(document).forEach { token -> getTokenMap(token).put(key(document, token, id), true) }
            return true
        }
        return false
    }

    fun search(tokens: Set<String>): Sequence<UUID> {
        val ranges = tokens.map { token -> tokenMap.keyIterator(MyKey.getRange(tokenToInt(token))).map { it.toSubKey() } }
        return ranges.mergeJoin2().map { it.id }
    }

    private inline fun key(document: Any, token: String, id: UUID) = MyKey(tokenToInt(token), (document as String).length, id)
}

/**
 * EXPERIMENTAL, UNDER DEVELOPMENT, DON'T USE.
 * Not thread-safe.
 * @param tokens produces tokens for a document, see [Analyzer.getTokens] for more info. If the closure returns an empty
 * set, that document is then ignored.
 */
class FullText2UMN(override val delegate: UMN, private val tokens: (Any) -> Set<String>) : UMN by delegate {
    private fun getTokenMap(token: String): UtilityMap<MySubKey, Boolean> = delegate.openUtilityMap("_FT2___$token", MySubKey.Type(), BooleanType())
    override fun create(document: Any): UUID {
        val id = delegate.create(document)
        tokens(document).forEach { token -> getTokenMap(token).put(key(document, token, id), true) }
        return id
    }

    override fun update(id: UUID, document: Any) {
        val oldDoc: Any = get(id, Any::class)
        val oldTokens = tokens(oldDoc)
        val newTokens = tokens(document)
        delegate.update(id, document)
        (oldTokens - newTokens).forEach { token -> getTokenMap(token).put(key(document, token, id), null) }
        (newTokens - oldTokens).forEach { token -> getTokenMap(token).put(key(document, token, id), true) }
    }

    override fun delete(id: UUID) {
        val oldDoc: Any = get(id, Any::class)
        val oldTokens = tokens(oldDoc)
        delegate.delete(id)
        oldTokens.forEach { token -> getTokenMap(token).put(key(oldDoc, token, id), null) }
    }

    override fun create(document: Any, id: UUID): Boolean {
        if (delegate.create(document, id)) {
            tokens(document).forEach { token -> getTokenMap(token).put(key(document, token, id), true) }
            return true
        }
        return false
    }

    fun search(tokens: Set<String>): Sequence<UUID> {
        val ranges = tokens.map { token -> getTokenMap(token) }
        return ranges.join().map { it.id }
    }

    private inline fun key(document: Any, token: String, id: UUID) = MySubKey((document as String).length, id)
}

data class MySubKey(val importance: Int, val id: UUID) : Comparable<MySubKey> {
    override fun compareTo(other: MySubKey): Int {
        var result = importance.compareTo(other.importance)
        if (result == 0) {
            result = id.compareTo(other.id)
        }
        return result
    }

    class Type : DataType {
        override fun write(buff: WriteBuffer, obj: Any) {
            val key = obj as MySubKey
            buff.putVarInt(key.importance)
            val id = key.id
            buff.putLong(id.mostSignificantBits)
            buff.putLong(id.leastSignificantBits)
        }

        override fun write(buff: WriteBuffer, obj: Array<out Any>, len: Int, key: Boolean) {
            for (i in 0 until len) {
                write(buff, obj[i])
            }
        }

        override fun compare(a: Any, b: Any): Int = (a as MySubKey).compareTo(b as MySubKey)

        override fun getMemory(obj: Any?): Int = 24 + 4 + 16

        override fun read(buff: ByteBuffer): Any {
            val importance = DataUtils.readVarInt(buff)
            val msb = buff.long
            val lsb = buff.long
            return MySubKey(importance, UUID(msb, lsb))
        }

        override fun read(buff: ByteBuffer, obj: Array<Any>, len: Int, key: Boolean) {
            for (i in 0 until len) {
                obj[i] = read(buff)
            }
        }
    }
}

data class MyKey(val token: Int, val importance: Int, val id: UUID) : Comparable<MyKey> {
    override fun compareTo(other: MyKey): Int {
        var result = token.compareTo(other.token)
        if (result == 0) {
            result = importance.compareTo(other.importance)
        }
        if (result == 0) {
            result = id.compareTo(other.id)
        }
        return result
    }

    fun toSubKey() = MySubKey(importance, id)

    class Type : DataType {
        override fun write(buff: WriteBuffer, obj: Any) {
            val key = obj as MyKey
            buff.putVarInt(key.token)
            buff.putInt(key.importance)
            val id = key.id
            buff.putLong(id.mostSignificantBits)
            buff.putLong(id.leastSignificantBits)
        }

        override fun write(buff: WriteBuffer, obj: Array<out Any>, len: Int, key: Boolean) {
            for (i in 0 until len) {
                write(buff, obj[i])
            }
        }

        override fun compare(a: Any, b: Any): Int = (a as MyKey).compareTo(b as MyKey)

        override fun getMemory(obj: Any?): Int = 24 + 4 + 4 + 16

        override fun read(buff: ByteBuffer): Any {
            val token = DataUtils.readVarInt(buff)
            val importance = buff.int
            val msb = buff.long
            val lsb = buff.long
            return MyKey(token, importance, UUID(msb, lsb))
        }

        override fun read(buff: ByteBuffer, obj: Array<Any>, len: Int, key: Boolean) {
            for (i in 0 until len) {
                obj[i] = read(buff)
            }
        }
    }

    companion object {
        private val minUUID = UUID(Long.MIN_VALUE, Long.MIN_VALUE)
        private val maxUUID = UUID(Long.MAX_VALUE, Long.MAX_VALUE)
        fun getRange(token:Int): ClosedRange<MyKey> {
            val min = MyKey(token, Int.MIN_VALUE, minUUID)
            val max = MyKey(token, Int.MAX_VALUE, maxUUID)
            return min..max
        }
    }
}

class BooleanType : DataType {
    override fun write(buff: WriteBuffer, obj: Any) {
        val o = obj as Boolean
        buff.put(if (o) 1.toByte() else 0)
    }

    override fun write(buff: WriteBuffer, obj: Array<out Any>, len: Int, key: Boolean) {
        for (i in 0 until len) {
            write(buff, obj[i])
        }
    }

    override fun compare(a: Any, b: Any): Int = (a as Boolean).compareTo(b as Boolean)

    override fun getMemory(obj: Any?): Int = 0

    override fun read(buff: ByteBuffer): Any = buff.get() != 0.toByte()

    override fun read(buff: ByteBuffer, obj: Array<Any>, len: Int, key: Boolean) {
        for (i in 0 until len) {
            obj[i] = read(buff)
        }
    }
}
