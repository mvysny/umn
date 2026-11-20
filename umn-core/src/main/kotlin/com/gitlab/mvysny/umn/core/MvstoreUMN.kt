package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVMap
import org.h2.mvstore.MVStore
import org.h2.mvstore.WriteBuffer
import org.h2.mvstore.type.DataType
import org.h2.mvstore.type.ObjectDataType
import java.io.*
import java.nio.ByteBuffer
import java.util.*
import kotlin.reflect.KClass

/**
 * Provides support for serializing/deserializing of objects. You can either use:
 * * The standard Java Serialization (by the means of [JavaDocumentSerializer])
 * * You can store the documents as JSON (say by using `Gson`)
 * * You can provide a completely custom serialization solution
 *
 * The document is always the last item of the stream, with no length in front of it, both in the store and in a
 * [LogRecord], whose bytes are frozen (`D_frozen_0_15_formats`). A format that doesn't mark its own end, ProtoBuf say,
 * reads the stream to its end; the README has a kotlinx.serialization example.
 */
public interface DocumentSerializer {
    /**
     * Serializes the [document] into the [dout] stream. Don't close the stream - it is automatically closed afterwards.
     * @param document the document to write to the stream.
     * @param dout write the document data here. Don't close. The stream is buffered or backed by a [ByteArrayOutputStream] so there is no
     * need to buffer it.
     */
    public fun serialize(document: Any, dout: DataOutputStream)

    /**
     * Deserializes the object of given [clazz] from given [din] input stream.
     * We need to know the class upfront: it is needed both by the JSON Serializer, and possibly by custom serializers.
     * @param din read the document data from here. No need to close. The stream is buffered or backed by a [ByteArrayInputStream] so
     * there is no need to buffer it.
     */
    public fun <T: Any> deserialize(clazz: KClass<T>, din: DataInputStream): T

    public fun serialize(document: Any): ByteArray = ByteArrayOutputStream().apply {
        serialize(document, DataOutputStream(this))
    }.toByteArray()

    public fun <T: Any> deserialize(clazz: KClass<T>, bytes: ByteArray): T = deserialize(clazz, DataInputStream(bytes.inputStream()))
}

/**
 * Uses standard Java Serialization to serialize/deserialize objects. **Do not use for production!**
 * * Unstable: The serialization format can change between different JVMs and between different JVM versions
 * * Slow
 * You should only be used for **testing** purposes.
 */
public object JavaDocumentSerializer : DocumentSerializer {
    override fun serialize(document: Any, dout: DataOutputStream) {
        ObjectOutputStream(dout).writeObject(document)
    }
    override fun <T : Any> deserialize(clazz: KClass<T>, din: DataInputStream): T = clazz.javaObjectType.cast(ObjectInputStream(din).readObject())
}

/**
 * Stores documents into H2 MVStore. Warning: [serializer] is used every time when a document is stored or retrieved!
 *
 * Thread-safe.
 *
 * Supports all [Serializable]/[Externalizable] keys; for more info please consult the documentation for H2 MV Store.
 *
 * The file stays small through MVStore's own auto-compaction, so open [store] with the default `autoCompactFillRate`
 * (`D_node_auto_compaction`).
 *
 * @property store the underlying store to use, closed when this UMN is closed.
 */
public class MvstoreUMN(private val store: MVStore, private val serializer: DocumentSerializer,
                 override val typeToClassResolver: TypeToClassResolver) : UMN {
    override val delegate: UMN?
        get() = null

    override fun create(document: Any, id: UUID): Boolean
            = map.putIfAbsent(id, serialize(document)) == null

    override fun close() {
        store.close()
    }

    private val map: MVMap<UUID, SerializedDocument> = store.openMap("ROOT2", MVMap.Builder<UUID, SerializedDocument>())

    /**
     * Stores every write so far; see [MVStore.commit].
     */
    internal fun commit() {
        store.commit()
    }

    private fun serialize(doc: Any): SerializedDocument = serializeData {
        val type = typeToClassResolver.toType(doc.javaClass.kotlin)
        writeShort(type.toInt())
        serializer.serialize(doc, this)
    }

    private fun deserialize(bytes: SerializedDocument): Pair<Any, Short> {
        val din = DataInputStream(ByteArrayInputStream(bytes))
        val type: DocType = din.readShort()
        return serializer.deserialize(typeToClassResolver.toClass(type), din) to type
    }

    override fun create(document: Any): UUID {
        val id = UUID.randomUUID()
        map[id] = serialize(document)
        return id
    }

    override fun <V : Any> find(id: UUID, clazz: KClass<V>): V? {
        val bytes = map[id]
        return if (bytes == null) null else clazz.javaObjectType.cast(deserialize(bytes).first)
    }

    override fun exists(id: UUID): Boolean = map.containsKey(id)

    override fun update(id: UUID, document: Any) {
        map.updateIfPresent(id) { _, _ -> serialize(document) } ?: throw IllegalArgumentException("Document with $id does not exist" )
    }

    override fun delete(id: UUID) {
        map.remove(id)
    }

    /**
     * Synchronized: MVStore's `openMap()` isn't atomic while the map doesn't exist yet. Two threads creating one map
     * get a map each, and what is written through the one whose name binding loses is orphaned.
     */
    @Synchronized
    override fun <K : Comparable<K>, V> openUtilityMap(name: String, keyType: DataType?, valueType: DataType?): UtilityMap<K, V> = MvstoreUtilityMap(store, name, keyType, valueType)

    /**
     * Synchronized with [openUtilityMap].
     *
     * ## Implementation notes
     *
     * `removeMap(map)` clears the map, freeing its pages (`R_mvstore_removemap_leaks`), and needs it open. A map not open
     * yet is opened with [OpaqueDataType] (`R_mvstore_clear_reads_no_key`), as `removeMap(name)` would open it with
     * `ObjectDataType`, reading its keys; an open one is removed as it is, since `openMap()` would return it, asserting
     * the types match (`R_mvstore_object_array_keys`).
     */
    @Synchronized
    override fun dropUtilityMap(name: String) {
        val mapName = "__$name"
        // MVStore's own lookup, private there
        val id = store.metaMap["name.$mapName"]?.toInt(16) ?: return
        val map: MVMap<Any, Any> = store.getMap(id)
            ?: store.openMap(mapName, MVMap.Builder<Any, Any>().keyType(OpaqueDataType).valueType(OpaqueDataType))
        store.removeMap(map)
    }

    override fun getSequenceOfIDs(): Sequence<UUID> = map.keyIterator(null).asSequence()

    override fun findSerialized(id: UUID): SerializedDocument? = map[id]

    override fun isEmpty(): Boolean = map.isEmpty()

    public companion object {
        /**
         * An MVStore held in memory only, gone once closed: for tests, and for anything that needs no file. It
         * serializes documents and keys and orders keys exactly as a file-backed store does, so a document or an
         * index key that doesn't serialize fails here, not first in production.
         */
        public fun inMemory(serializer: DocumentSerializer, typeToClassResolver: TypeToClassResolver): MvstoreUMN =
            MvstoreUMN(MVStore.open(null), serializer, typeToClassResolver)
    }
}

/**
 * Stores every write made so far, when an [MvstoreUMN] ends this chain; a no-op over any other store. A write that must never
 * be stored without an earlier one, to another map, goes after this call: MVStore alone stores map by map, and may keep the
 * later write and lose the earlier (`R_mvstore_store_per_map`).
 */
internal fun UMN.commit() {
    generateSequence(this) { it.delegate }.filterIsInstance<MvstoreUMN>().firstOrNull()?.commit()
}

/**
 * Obtain one through [MvstoreUMN.openUtilityMap]: two constructors racing on a map not created yet split it in two.
 *
 * Thread-safe.
 */
public class MvstoreUtilityMap<K: Comparable<K>, V>(store: MVStore, name: String, keyType: DataType?, valueType: DataType?) : UtilityMap<K, V> {
    override fun keyCount(): Int = map.size

    override fun keyPage(indexRange: IntRange): List<K> {
        if (indexRange.isEmpty()) return listOf()
        require(indexRange.start >= 0) { "$indexRange contains negative indices" }
        val startingKey = map.getKey(indexRange.first.toLong()) ?: return listOf()
        val length = minOf(indexRange.last.toLong() - indexRange.first + 1, Int.MAX_VALUE.toLong()).toInt()
        return map.keyIterator(startingKey).asSequence().take(length).toList()
    }

    override fun containsKey(key: K): Boolean = map.containsKey(key)

    override fun keyIterator(range: ClosedRange<K>?): Sequence<K> {
        if (range == null) return map.keyIterator(null).asSequence()
        if (range.isEmpty()) return emptySequence()
        val keySequence = map.keyIterator(range.start).asSequence()
        return keySequence.takeWhile { key -> key in range }
    }

    private val map: MVMap<K, V> = store.openMap("__$name", MVMap.Builder<K, V>().keyType(keyType).valueType(valueType))
    override fun find(key: K): V? = map[key]
    override fun put(key: K, value: V?) {
        // here, not at serialization: MVStore serializes later, on its background writer thread
        require(key !is CompositeKey || !key.isRangeBound) { "$key is a range bound, not a key" }
        map.putOrRemove(key, value)
    }
    override fun getSmallestKey(): K? = map.firstKey()
    override fun getLargestKey(): K? = map.lastKey()

    override fun values(): Iterator<V> = map.values.iterator()

    override fun clear() {
        map.clear()
    }

    override fun isEmpty(): Boolean = map.isEmpty()

    override fun lowerKey(key: K): K? = map.lowerKey(key)
}

/**
 * Reads a page's keys, or a leaf's values, as one opaque blob: the rest of the page. For [MvstoreUMN.dropUtilityMap] only,
 * whose `clear()` needs an inner page's child positions alone, which come before its keys (`R_mvstore_clear_reads_no_key`).
 * Never writes, never compares.
 *
 * A copy of H2's `MVStoreTool.GenericDataType`, package-private there.
 */
private object OpaqueDataType : DataType {
    override fun compare(a: Any?, b: Any?): Int = throw UnsupportedOperationException("opaque, can't compare")

    override fun getMemory(obj: Any?): Int = (obj as ByteArray?)?.size ?: 0

    override fun write(buff: WriteBuffer, obj: Any?): Unit = throw UnsupportedOperationException("opaque, read-only")

    override fun write(buff: WriteBuffer, obj: Array<out Any?>, len: Int, key: Boolean): Unit =
        throw UnsupportedOperationException("opaque, read-only")

    override fun read(buff: ByteBuffer): Any? =
        if (buff.hasRemaining()) ByteArray(buff.remaining()).also { buff.get(it) } else null

    override fun read(buff: ByteBuffer, obj: Array<Any?>, len: Int, key: Boolean) {
        for (i in 0 until len) obj[i] = read(buff)
    }
}

/**
 * An index map's key type: [ObjectDataType], which also stores a [CompositeKey] as its `Object[]` of parts. Writes the same
 * bytes as a plain [ObjectDataType], which MVStore 1.4 doesn't record in the file (`R_mvstore_object_array_keys`), so
 * indices written before, and by 0.15, read unchanged.
 *
 * Only index maps: code reading a utility map with a raw `MVStore.openMap()`, such as `umn-compat-0.15`'s for the sync
 * maps, opens it with a plain [ObjectDataType], and one open map takes one key type.
 */
internal class CompositeKeyDataType : DataType {
    private val delegate = ObjectDataType()

    private fun toStorage(obj: Any?): Any? = if (obj is CompositeKey) obj.toStorage() else obj

    override fun compare(a: Any?, b: Any?): Int =
        if (a is CompositeKey && b is CompositeKey) a.compareTo(b) else delegate.compare(toStorage(a), toStorage(b))

    override fun getMemory(obj: Any?): Int = delegate.getMemory(toStorage(obj))

    override fun write(buff: WriteBuffer, obj: Any?) {
        delegate.write(buff, toStorage(obj))
    }

    override fun write(buff: WriteBuffer, obj: Array<out Any?>, len: Int, key: Boolean) {
        for (i in 0 until len) write(buff, obj[i])
    }

    // K is Comparable, so an array read back can only be a CompositeKey's parts
    override fun read(buff: ByteBuffer): Any? = delegate.read(buff).let { if (it is Array<*>) CompositeKey.fromStorage(it) else it }

    override fun read(buff: ByteBuffer, obj: Array<Any?>, len: Int, key: Boolean) {
        for (i in 0 until len) obj[i] = read(buff)
    }
}
