package com.gitlab.mvysny.umn.core

import org.h2.mvstore.type.ObjectDataType
import java.math.BigInteger
import java.util.*

/**
 * An index key of several parts, ordered part by part: index a document under its category and
 * then its position, and list one category in order with [For.prefix].
 *
 * ```
 * val idxItems = IndexingUMN.IndexDef.simple<CompositeKey, Item>("items",
 *     { item -> setOf(CompositeKey.of(item.categoryId, item.ordinal)) }, Item::class)
 *
 * umn.getIndex(idxItems).findAllDocs(For.prefix(CompositeKey.of(categoryId)))   // ordered by ordinal
 * ```
 *
 * An index on [MvstoreUMN] stores it as an `Object[]` of the parts, natively, with no class names and
 * no Java serialization, so renaming or obfuscating app classes never breaks the index. A plain
 * [UMN.openUtilityMap] doesn't: it would Java-serialize it, and it isn't `Serializable`. A key sorts before
 * every longer key it is a prefix of: `("a") < ("a", 1) < ("a", 2) < ("b")`.
 *
 * Keep one type per position: parts of different types sort by type, not by value, so `Int` 5
 * sorts before `Long` 3.
 *
 * Immutable.
 *
 * ## Implementation notes
 *
 * [compareTo] reproduces H2's own `Object[]` order — each part by [ObjectDataType], then the shorter
 * key first — so a range walked in memory agrees with the B-tree. `R_mvstore_object_array_keys`.
 */
public class CompositeKey private constructor(
    private val parts: Array<Any>,
    /** True for [prefixRange]'s upper end: sorts after every key that starts with [parts]. Never stored. */
    private val upperBound: Boolean
) : Comparable<CompositeKey> {

    public val size: Int get() = parts.size

    public operator fun get(index: Int): Any = parts[index]

    public fun toList(): List<Any> = parts.toList()

    /**
     * Every key that starts with this one's parts, this key included; [For.prefix] wraps it for an index query.
     */
    public fun prefixRange(): ClosedRange<CompositeKey> = this..CompositeKey(parts, true)

    internal val isRangeBound: Boolean get() = upperBound

    /**
     * The parts as H2 stores them; an `Object[]`, never a typed array, which H2 would store as a different type.
     */
    internal fun toStorage(): Array<Any> {
        check(!upperBound) { "$this is a range bound, not a key" }
        return parts
    }

    override fun compareTo(other: CompositeKey): Int {
        for (i in 0 until minOf(parts.size, other.parts.size)) {
            val c = partOrder.compare(parts[i], other.parts[i])
            if (c != 0) return c
        }
        if (parts.size == other.parts.size) return upperBound.compareTo(other.upperBound)
        // one is a prefix of the other: the prefix sorts first, unless it is an upper bound
        return if (parts.size < other.parts.size) {
            if (upperBound) 1 else -1
        } else {
            if (other.upperBound) -1 else 1
        }
    }

    override fun equals(other: Any?): Boolean =
        other is CompositeKey && upperBound == other.upperBound && parts.contentEquals(other.parts)

    override fun hashCode(): Int = parts.contentHashCode() * 31 + upperBound.hashCode()

    override fun toString(): String =
        parts.joinToString(prefix = "(", postfix = if (upperBound) ", *)" else ")")

    public companion object {
        // Stateless but for a cache of the last type seen, a hint every type re-checks: safe to share across threads.
        private val partOrder = ObjectDataType()

        private val partTypes: Set<Class<*>> = setOf(
            String::class.java, Boolean::class.javaObjectType, Char::class.javaObjectType,
            Byte::class.javaObjectType, Short::class.javaObjectType, Int::class.javaObjectType,
            Long::class.javaObjectType, Float::class.javaObjectType, Double::class.javaObjectType,
            BigInteger::class.java, UUID::class.java
        )

        /**
         * @param parts each a [String], a boxed primitive, a [BigInteger] or a [UUID]: a type H2 stores natively,
         *   in a fixed order. No `Date` (mutable; store its `time`), no `BigDecimal` (`1.0` and `1.00` are one key to
         *   H2 but unequal to Kotlin).
         * @throws IllegalArgumentException when [parts] is empty or a part has another type.
         */
        public fun of(vararg parts: Any): CompositeKey {
            require(parts.isNotEmpty()) { "a CompositeKey needs at least one part" }
            for (part in parts) {
                require(part.javaClass in partTypes) { "${part.javaClass.name} can't be a CompositeKey part: $part" }
            }
            return CompositeKey(Array(parts.size) { parts[it] }, false)
        }

        /**
         * Rebuilds a key from the `Object[]` H2 read back.
         */
        internal fun fromStorage(parts: Array<*>): CompositeKey {
            @Suppress("UNCHECKED_CAST")
            return CompositeKey(parts as Array<Any>, false)
        }
    }
}
