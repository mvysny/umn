package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaTest
import org.h2.mvstore.MVMap
import org.h2.mvstore.MVStore
import java.io.DataInputStream
import java.util.*
import kotlin.test.expect
import java.io.File

class MvstoreUMNTest : DynaTest({
    group("in-memory tests") {
        genericUmnTestBattery { MvstoreUMN(MVStore.open(null), JavaDocumentSerializer, AutoTypeResolver) }
    }

    group("file-based tests") {
        genericUmnTestBattery { createMvstoreUMN() }
    }

    group("migration v1->v2 tests") {
        lateinit var store: MVStore
        lateinit var root: MVMap<UUID, ByteArray>
        lateinit var umn: MvstoreUMN
        beforeEach {
            store = MVStore.open(File.createTempFile("tmp", null).absolutePath)
            root = store.openMap<UUID, ByteArray>("ROOT")
            umn = MvstoreUMN(store, JavaDocumentSerializer, AutoTypeResolver)
        }
        afterEach { umn.close() }

        test("migration runs on empty db") {
            umn.migrateV1V2 { org.junit.jupiter.api.fail { "should not be called since there are no records" } }
            umn.migrateV1V2 { org.junit.jupiter.api.fail { "should not be called since there are no records" } }
        }

        test("migration runs on simple db") {
            val id = UUID.randomUUID()
            root[id] = JavaDocumentSerializer.serialize("foo")
            umn.migrateV1V2 { uuid ->
                expect(id) { uuid }
                AutoTypeResolver.toType(String::class)
            }
            expect(0) { root.size }
            expect("foo") { umn.get(id, String::class) }
            expect(AutoTypeResolver.toType(String::class)) { umn.findSerialized(id)!!.type }
        }

        test("migration runs only once") {
            val id = UUID.randomUUID()
            root[id] = JavaDocumentSerializer.serialize("foo")
            umn.migrateV1V2 { _ -> AutoTypeResolver.toType(String::class) }
            expect("foo") { umn.get(id, String::class) }
            umn.update(id, "bar")
            umn.migrateV1V2 { org.junit.jupiter.api.fail { "should not be called since there are no records" } }
            expect("bar") { umn.get(id, String::class) }
        }
    }
})

private val SerializedDocument.type: Short
    get() = DataInputStream(inputStream()).readShort()
