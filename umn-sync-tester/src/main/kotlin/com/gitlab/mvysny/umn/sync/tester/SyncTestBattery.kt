package com.gitlab.mvysny.umn.sync.tester

import com.gitlab.mvysny.umn.core.*
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.SyncingUMN
import com.gitlab.mvysny.umn.sync.syncing
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.expect

/**
 * Tests a scenario with 1 writer and possibly multitude of readers receiving the changes that writer made;
 * run it as a nested class of the client's own test:
 *
 * ```
 * @Nested inner class OnInMemory : WriterReaderTestBattery(3) {
 *     override fun newStorage(): UMN = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer)
 *     override fun newClient(): GlobalLogClient = log.newClient()
 * }
 * ```
 *
 * The battery's own `@BeforeEach` calls [newStorage] and [newClient], before any `@BeforeEach` of
 * a subclass: create the global log in an enclosing class.
 * @param readerCount number of readers, 1 or higher
 */
abstract class WriterReaderTestBattery(private val readerCount: Int) {
    init {
        require(readerCount >= 1) { "$readerCount must be 1 or higher" }
    }

    /**
     * A basic root storage, [com.gitlab.mvysny.umn.core.InMemoryUMN] or [com.gitlab.mvysny.umn.core.MvstoreUMN].
     */
    abstract fun newStorage(): UMN

    abstract fun newClient(): GlobalLogClient

    /**
     * Takes the server down: every connection drops, and new clients fail to connect until
     * [serverUp]. A client with no connection to lose leaves it be, which skips the reconnect
     * tests.
     */
    open fun serverDown(): Unit = Assumptions.abort("${javaClass.simpleName} has no connection to lose")

    /**
     * Brings the server back after [serverDown], with the same global log.
     */
    open fun serverUp() {}

    private var isServerDown = false
    private fun takeServerDown() {
        serverDown()
        isServerDown = true
    }
    private fun bringServerUp() {
        serverUp()
        isServerDown = false
    }

    private val idx = IndexingUMN.IndexDef.simple("length", { setOf(it.length) }, String::class)
    private fun createUMN(): UMN {
        val storage = newStorage()
        val indexing = IndexingUMN(storage, setOf(idx))
        val syncing = indexing.syncing(::newClient, JavaDocumentSerializer, true)
        return syncing
    }

    private lateinit var writer: UMN
    private lateinit var readers: List<UMN>
    @BeforeEach fun createUMNs() {
        writer = createUMN()
        readers = List(readerCount) { createUMN() }
    }
    @AfterEach fun closeUMNs() {
        writer.close()
        readers.forEach { it.close() }
        if (isServerDown) bringServerUp()
    }
    private fun allClients(): List<UMN> = readers + writer

    @Test fun `all UMNs are initially empty`() {
        val id = UUID.randomUUID()
        allClients().forEach { reader ->
            expect(false) { reader.exists(id) }
            expect(null) { reader.find(id, String::class) }
            expect(true) { reader.isEmpty() }
        }
    }
    @Test fun `create() propagates properly`() {
        val id = writer.create("askdj124389akj")
        allClients().forEach { reader ->
            expectAsync(true) { reader.exists(id) }
        }
    }
    @Test fun `propagation creates indices properly`() {
        val id = writer.create("askdj124389akj")
        allClients().forEach { umn ->
            expectAsync(true) { umn.exists(id) }
            // a node applies a pulled record to its storage first, its indices a moment later
            expectAsync(id to "askdj124389akj") { umn.facet<IndexingUMN>().getIndex(idx).findFirstDoc(For.Key(14)) }
        }
    }
    @Test fun `create()+update() propagates properly`() {
        val id = writer.create("askdj124389akj")
        writer.update(id, "foo")
        allClients().forEach { umn ->
            expectAsync("foo") { umn.find(id, String::class) }
            expectAsync(null) { umn.facet<IndexingUMN>().getIndex(idx).findFirstDoc(For.Key(14)) }
            expectAsync(id to "foo") { umn.facet<IndexingUMN>().getIndex(idx).findFirstDoc(For.Key(3)) }
        }
    }
    @Test fun `create()+update()+delete() propagates properly`() {
        val id = writer.create("askdj124389akj")
        writer.update(id, "foo")
        writer.delete(id)
        allClients().forEach { umn ->
            expectAsync(null) { umn.find(id, String::class) }
            expectAsync(null) { umn.facet<IndexingUMN>().getIndex(idx).findFirstDoc(For.Key(14)) }
            expectAsync(null) { umn.facet<IndexingUMN>().getIndex(idx).findFirstDoc(For.Key(3)) }
        }
    }

    @Test fun `sync resumes once the server is back`() {
        val before = writer.create("before")
        allClients().forEach { umn -> expectAsync(true) { umn.exists(before) } }
        takeServerDown()
        val fromWriter = writer.create("from writer")
        val fromReader = readers[0].create("from reader")
        bringServerUp()
        allClients().forEach { umn ->
            expectAsync(true, 10_000) { umn.exists(fromWriter) && umn.exists(fromReader) }
        }
    }

    @Test fun `a node started while the server is down syncs once it is up`() {
        takeServerDown()
        createUMN().use { late ->
            val fromLate = late.create("from late")
            bringServerUp()
            allClients().forEach { umn -> expectAsync(true, 10_000) { umn.exists(fromLate) } }
        }
    }
}

/**
 * Tests a more realistic scenario with n equivalent nodes, starting off a different state: adding sync
 * to already existing UMN databases syncs their documents. Run it like [WriterReaderTestBattery].
 * @param nodeCount number of nodes, 2 or higher
 * @param awaitSync after starting [SyncingUMN], expect that a full sync will take at most this amount of milliseconds.
 */
abstract class SyncTestBattery(private val nodeCount: Int, private val awaitSync: Long = 300) {
    init {
        require(nodeCount >= 2) { "$nodeCount must be 2 or higher" }
    }

    /**
     * A basic root storage, [com.gitlab.mvysny.umn.core.InMemoryUMN] or [com.gitlab.mvysny.umn.core.MvstoreUMN].
     */
    abstract fun newStorage(): UMN

    /**
     * Produces new unstarted client for every test.
     */
    abstract fun newClient(): GlobalLogClient

    private val idx = IndexingUMN.IndexDef.simple("length", { setOf(it.length) }, String::class)

    private lateinit var nodes: List<UMN>
    @BeforeEach fun createNodes() { nodes = List(nodeCount) { IndexingUMN(newStorage(), setOf(idx)) } }
    @AfterEach fun closeNodes() { nodes.forEach { it.close() } }

    private fun withSync(awaitSync: Long, block: ()->Unit) {
        val oldNodes = nodes
        nodes = nodes.map { it.syncing(::newClient, JavaDocumentSerializer, true) }
        try {
            // the sync runs in bg, wait a bit until all changes are distributed
            Thread.sleep(awaitSync)
            block()
        } finally {
            nodes.forEach { (it as SyncingUMN).syncer.apply { close(); awaitClose() } }
        }
        nodes = oldNodes
    }

    @Test fun `create() propagates properly`() {
        val uuids = mutableMapOf<UUID, String>()
        nodes.forEach { node ->
            (0 until Random().nextInt(50)).forEach {
                val doc = "foo $it"
                uuids[node.create(doc)] = doc
            }
        }
        withSync(awaitSync) {
            // check that the documents were propagated properly to all nodes
            nodes.forEach { node ->
                uuids.forEach { (id, doc) -> expect(doc) { node.get(id, Any::class) } }
            }
        }
    }

    @Test fun `create()+update() propagates properly`() {
        val uuids = mutableMapOf<UUID, String>()
        nodes.forEach { node ->
            (0 until Random().nextInt(50)).forEach {
                val doc = "foo $it"
                val updated = "bar $it"
                val id = node.create(doc)
                node.update(id, updated)
                uuids.put(id, updated)
            }
        }
        withSync(awaitSync) {
            // check that the documents were propagated properly to all nodes
            nodes.forEach { node ->
                uuids.forEach { (id, doc) -> expect(doc) { node.get(id, Any::class) } }
            }
        }
    }

    @Test fun `deleted documents are not propagated`() {
        nodes.forEach { node ->
            (0 until Random().nextInt(50)).forEach { node.delete(node.create("foo $it")) }
        }
        withSync(awaitSync) {
            // check that the documents were propagated properly to all nodes
            nodes.forEach { node ->
                expect(listOf()) { node.getSequenceOfIDs().toList() }
                expect(true) { node.isEmpty() }
            }
        }
    }

    @Test fun `late sync syncs only once`() {
        val id = nodes[0].create("foo")
        withSync(awaitSync) {
            nodes.forEach { it.get(id, String::class) }  // check that the change is propagated
        }
        Thread.sleep(200)
        nodes[1].delete(id) // delete the document without telling sync. This is awful practice but allows us
        // to check that sync will not resync all documents from all nodes again.
        withSync(awaitSync) {
            // not resynced
            expect(false) { nodes[1].exists(id) }
            // but the doc still exists on other node. This is an example of how to break sync.
            expect("foo") { nodes[0].get(id, String::class) }
        }
    }
}

val Int.seconds: Long get() = this * 1000L
