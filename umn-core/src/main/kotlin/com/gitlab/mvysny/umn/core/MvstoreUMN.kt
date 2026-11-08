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
    override fun <T : Any> deserialize(clazz: KClass<T>, din: DataInputStream): T = clazz.java.cast(ObjectInputStream(din).readObject())
}

/**
 * Stores documents into H2 MVStore. Warning: [serializer] is used every time when a document is stored or retrieved!
 *
 * Thread-safe.
 *
 * Supports all [Serializable]/[Externalizable] keys; for more info please consult the documentation for H2 MV Store.
 *
 * @property store the underlying store to use, closed when this UMN is closed.
 */
public class MvstoreUMN(private val store: MVStore, private val serializer: DocumentSerializer,
                 override val typeToClassResolver: TypeToClassResolver) : UMN {
    init {
        // this radically decreases database size: https://github.com/h2database/h2database/issues/1835
        // but this could introduce huge initial pause. Investigate setRetentionTime()
//        store.compactRewriteFully()
//        store.compactMoveChunks()
    }
    override val delegate: UMN?
        get() = null

    override fun create(document: Any, id: UUID): Boolean
            = map.putIfAbsent(id, serialize(document)) == null

    override fun close() {
        // this radically decreases database size: https://github.com/h2database/h2database/issues/1835
        // but this could introduce huge initial pause. Investigate setRetentionTime()
//        store.compactRewriteFully()
//        store.compactMoveChunks()
        store.close()
    }

    private val map: MVMap<UUID, SerializedDocument> = store.openMap("ROOT2", MVMap.Builder<UUID, SerializedDocument>())

    /**
     * Migrates v1 database to v2. Only needs to be run once. Warning: thread-unsafe! Needs to be run after this class
     * is constructed, but before it is accessed by other parties.
     *
     * The v2 database added support for document having types.
     *
     * The function does nothing if the database is already migrated.
     *
     * If the [typeResolver] throws an exception, the whole function fails and leaves the db half-migrated. When this function
     * is re-run, it will clean up and start the migration anew.
     *
     * @param typeResolver resolves the type for given document. Note that you CAN'T load the document using [get] - hopefully
     * you had every document indexed by its type previously :-D
     * since that only works on v2 database (which is not yet populated).
     */
    public fun migrateV1V2(typeResolver: (UUID)->Short) {
        // migrate to v2 with types
        val oldMap: MVMap<UUID, ByteArray> = store.openMap("ROOT")
        if (oldMap.isNotEmpty()) {
            map.clear()
            oldMap.keyIterator(null).forEach { id ->
                val clazz = typeToClassResolver.toClass(typeResolver(id))
                val doc = serializer.deserialize(clazz, oldMap[id]!!)
                map[id] = serialize(doc)
            }
            oldMap.clear()
        }
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
        return if (bytes == null) null else clazz.java.cast(deserialize(bytes).first)
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

    override fun getSequenceOfIDs(): Sequence<UUID> = map.keyIterator(null).asSequence()

    override fun findSerialized(id: UUID): SerializedDocument? = map[id]

    override fun isEmpty(): Boolean = map.isEmpty()

    /**
     * Compacts the database file.
     */
    public fun compact() {
        store.compactRewriteFully()
        store.compactMoveChunks()
    }

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
