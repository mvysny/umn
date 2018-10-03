package com.gitlab.mvysny.umn.sync.tester

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.expectList
import com.gitlab.mvysny.umn.core.*
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.SyncingUMN
import com.gitlab.mvysny.umn.sync.syncing
import java.util.*
import kotlin.test.expect

/**
 * Tests a scenario with 1 writer and possibly multitude of readers receiving the changes that writer made.
 * @param readerCount number of readers, 1 or higher
 * @param umnStorageProducer a basic root storage, [com.gitlab.mvysny.umn.core.InMemoryUMN] or [com.gitlab.mvysny.umn.core.MvstoreUMN].
 */
fun DynaNodeGroup.writerReaderTestBattery(umnType: String, readerCount: Int, umnStorageProducer: ()->UMN, clientProducer: ()-> GlobalLogClient) {
    require(readerCount >= 1) { "$readerCount must be 1 or higher" }
    val idx = IndexingUMN.IndexDef.simple("length", { setOf(it.length) }, String::class)
    fun createUMN(): UMN {
        val storage = umnStorageProducer()
        val indexing = IndexingUMN(storage, setOf(idx))
        val syncing = indexing.syncing(clientProducer, JavaDocumentSerializer, true)
        return syncing
    }

    group("sync test with 1 writer and $readerCount reader(s) on $umnType") {
        lateinit var writer: UMN
        lateinit var readers: List<UMN>
        beforeEach {
            writer = createUMN()
            readers = List(readerCount) { createUMN() }
        }
        afterEach {
            writer.close()
            readers.forEach { it.close() }
        }
        fun allClients(): List<UMN> = readers + writer

        test("all UMNs are initially empty") {
            val id = UUID.randomUUID()
            allClients().forEach { reader ->
                expect(false) { reader.exists(id) }
                expect(null) { reader.find(id, String::class) }
                expect(true) { reader.isEmpty() }
            }
        }
        test("create() propagates properly") {
            val id = writer.create("askdj124389akj")
            allClients().forEach { reader ->
                expectAsync(true) { reader.exists(id) }
            }
        }
        test("propagation creates indices properly") {
            val id = writer.create("askdj124389akj")
            allClients().forEach { umn ->
                expectAsync(true) { umn.exists(id) }
                expect(id to "askdj124389akj") { umn.facet<IndexingUMN>().getIndex(idx).findFirstDoc(For.Key(14)) }
            }
        }
        test("create()+update() propagates properly") {
            val id = writer.create("askdj124389akj")
            writer.update(id, "foo")
            allClients().forEach { umn ->
                expectAsync("foo") { umn.find(id, String::class) }
                expect(null) { umn.facet<IndexingUMN>().getIndex(idx).findFirstDoc(For.Key(14)) }
                expect(id to "foo") { umn.facet<IndexingUMN>().getIndex(idx).findFirstDoc(For.Key(3)) }
            }
        }
        test("create()+update()+delete() propagates properly") {
            val id = writer.create("askdj124389akj")
            writer.update(id, "foo")
            writer.delete(id)
            allClients().forEach { umn ->
                expectAsync(null) { umn.find(id, String::class) }
                expect(null) { umn.facet<IndexingUMN>().getIndex(idx).findFirstDoc(For.Key(14)) }
                expect(null) { umn.facet<IndexingUMN>().getIndex(idx).findFirstDoc(For.Key(3)) }
            }
        }
    }
}

/**
 * Tests a more realistic scenario with n equivalent nodes, starting off a different state.
 * @param nodeCount number of nodes, 2 or higher
 * @param umnStorageProducer a basic root storage, [com.gitlab.mvysny.umn.core.InMemoryUMN] or [com.gitlab.mvysny.umn.core.MvstoreUMN].
 * @param clientProducer produces new unstarted client for every test.
 * @param awaitSync after starting [SyncingUMN], expect that a full sync will take at most this amount of time.
 */
fun DynaNodeGroup.syncTestBattery(umnType: String, nodeCount: Int, umnStorageProducer: ()->UMN,
                                  clientProducer: ()-> GlobalLogClient,
                                  awaitSync: Long = 300) {
    require(nodeCount >= 1) { "$nodeCount must be 1 or higher" }
    val idx = IndexingUMN.IndexDef.simple("length", { setOf(it.length) }, String::class)

    // tests that adding sync to already existing UMN databases will sync the documents.
    group("late sync test with $nodeCount nodes on $umnType") {
        lateinit var nodes: List<UMN>
        beforeEach { nodes = List(nodeCount) { IndexingUMN(umnStorageProducer(), setOf(idx)) } }
        afterEach { nodes.forEach { it.close() } }

        fun withSync(awaitSync: Long, block: ()->Unit) {
            val oldNodes = nodes
            nodes = nodes.map { it.syncing(clientProducer, JavaDocumentSerializer, true) }
            try {
                // the sync runs in bg, wait a bit until all changes are distributed
                Thread.sleep(awaitSync)
                block()
            } finally {
                nodes.forEach { (it as SyncingUMN).syncer.apply { close(); awaitClose() } }
            }
            nodes = oldNodes
        }

        test("create() propagates properly") {
            val uuids = mutableMapOf<UUID, String>()
            nodes.forEach { node ->
                (0 until Random().nextInt(50)).forEach {
                    val doc = "foo $it"
                    uuids.put(node.create(doc), doc)
                }
            }
            withSync(awaitSync) {
                // check that the documents were propagated properly to all nodes
                nodes.forEach { node ->
                    uuids.forEach { id, doc -> expect(doc) { node.get(id, Any::class) } }
                }
            }
        }

        test("create()+update() propagates properly") {
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
                    uuids.forEach { id, doc -> expect(doc) { node.get(id, Any::class) } }
                }
            }
        }

        test("deleted documents are not propagated") {
            nodes.forEach { node ->
                (0 until Random().nextInt(50)).forEach { node.delete(node.create("foo $it")) }
            }
            withSync(awaitSync) {
                // check that the documents were propagated properly to all nodes
                nodes.forEach { node ->
                    expectList() { node.getSequenceOfIDs().toList() }
                    expect(true) { node.isEmpty() }
                }
            }
        }

        test("late sync syncs only once") {
            val id = nodes[0].create("foo")
            val oldNodes = nodes
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
}

val Int.seconds: Long get() = this * 1000L
