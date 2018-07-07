package com.gitlab.mvysny.umn.sync.tester

import com.github.mvysny.dynatest.DynaNodeGroup
import com.gitlab.mvysny.umn.core.IndexingUMN
import com.gitlab.mvysny.umn.core.UMN
import com.gitlab.mvysny.umn.core.facet
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.syncing
import java.util.*
import kotlin.test.expect

/**
 * Tests a scenario with 1 writer and possibly multitude of readers receiving the changes that writer made.
 * @param readerCount number of readers, 1 or higher
 * @param umnStorageProducer a basic root storage, [com.gitlab.mvysny.umn.core.InMemoryUMN] or [com.gitlab.mvysny.umn.core.MvstoreUMN].
 */
fun DynaNodeGroup.syncTestBattery(readerCount: Int, umnStorageProducer: ()->UMN, clientProducer: ()-> GlobalLogClient) {
    require(readerCount >= 1) { "$readerCount must be 1 or higher" }
    fun createUMN(): UMN {
        val storage = umnStorageProducer()
        val indexing = IndexingUMN(storage, setOf(IndexingUMN.IndexDef("length", { setOf((it as String).length) })))
        val syncing = indexing.syncing(clientProducer())
        return syncing
    }

    group("sync test with 1 writer and $readerCount reader(s)") {
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
                expect("askdj124389akj") { umn.facet<IndexingUMN>().getIndex<Int>("length").findOneByKey(14, String::class) }
            }
        }
        test("create()+update() propagates properly") {
            val id = writer.create("askdj124389akj")
            writer.update(id, "foo", String::class)
            allClients().forEach { umn ->
                expectAsync("foo") { umn.find(id, String::class) }
                expect(null) { umn.facet<IndexingUMN>().getIndex<Int>("length").findOneByKey(14, String::class) }
                expect("foo") { umn.facet<IndexingUMN>().getIndex<Int>("length").findOneByKey(3, String::class) }
            }
        }
        test("create()+update()+delete() propagates properly") {
            val id = writer.create("askdj124389akj")
            writer.update(id, "foo", String::class)
            writer.delete(id, String::class)
            allClients().forEach { umn ->
                expectAsync(null) { umn.find(id, String::class) }
                expect(null) { umn.facet<IndexingUMN>().getIndex<Int>("length").findOneByKey(14, String::class) }
                expect(null) { umn.facet<IndexingUMN>().getIndex<Int>("length").findOneByKey(3, String::class) }
            }
        }
    }
}
