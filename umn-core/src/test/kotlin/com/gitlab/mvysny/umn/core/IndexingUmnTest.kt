package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaTest
import kotlin.test.expect

class IndexingUmnTest : DynaTest({
    group("basic tests") {
        genericUmnTestBattery {
            val indexGenerator: (Any) -> Set<Int> = { doc -> setOf(System.identityHashCode(doc)) }
            IndexingUmn("identityHashCode", InMemoryUmn(), indexGenerator)
        }
    }

    test("findOneByKey returns null on empty map") {
        val umn = IndexingUmn("identity", InMemoryUmn(), { doc -> setOf(doc as String) })
        expect(null) { umn.findOneByKey("", String::class) }
        expect(null) { umn.findOneByKey("foo", String::class) }
    }

    test("findOneByKey correctly finds the document") {
        val umn = IndexingUmn("identity", InMemoryUmn(), { doc -> setOf(doc as String) })
        umn.create("kvak")
        expect("kvak") { umn.findOneByKey("kvak", String::class) }
    }

    test("findOneByKey correctly updates the indices on update()") {
        val umn = IndexingUmn("identity", InMemoryUmn(), { doc -> setOf(doc as String) })
        val id = umn.create("kvak")
        umn.update(id, "foo", String::class)
        expect("foo") { umn.findOneByKey("foo", String::class) }
        expect(null) { umn.findOneByKey("kvak", String::class) }
    }
})