package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.expectThrows
import java.util.*
import kotlin.test.expect

/**
 * A generic test battery to test basic properties of a UMN storage.
 */
fun DynaNodeGroup.genericUmnTestBattery(emptyStorageProvider: () -> UMN) {
    lateinit var umn: UMN
    beforeEach { umn = emptyStorageProvider() }
    afterEach { umn.close() }

    test("document initially does not exist") {
        val id = UUID.randomUUID()
        expect(false) { umn.exists(id) }
        expect(null) { umn.find(id, String::class) }
    }
    test("create of document should succeed") {
        umn.create("testing document")
    }
    test("re-reading of created document should succeed") {
        val id = umn.create("testing document")
        expect("testing document") { umn.find(id, String::class)!! }
    }
    test("created document should exist") {
        val id = umn.create("testing document")
        expect(true) { umn.exists(id) }
    }
    group("update") {
        test("created document should be updatable") {
            val id = umn.create("testing document")
            umn.update(id, "foo", String::class)
            expect("foo") { umn.get(id, String::class) }
        }
        test("update of non-existing document should fail") {
            expectThrows(IllegalArgumentException::class) {
                umn.update(UUID.randomUUID(), "foo", String::class)
            }
        }
    }
    test("document no longer exists after delete") {
        val id = umn.create("testing document")
        umn.delete(id, String::class)
        expect(false) { umn.exists(id) }
        expect(null) { umn.find(id, String::class) }
    }
}
