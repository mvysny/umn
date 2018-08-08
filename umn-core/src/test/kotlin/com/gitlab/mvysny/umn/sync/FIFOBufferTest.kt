package com.gitlab.mvysny.umn.sync

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.DynaTest
import com.gitlab.mvysny.umn.core.*
import kotlin.test.expect

internal fun DynaNodeGroup.fifoBufferTestBattery(bufferProducer: () -> FIFOBuffer<String>) {
    lateinit var buf: FIFOBuffer<String>
    beforeEach { buf = bufferProducer() }
    test("Initially the buffer is empty") {
        expect(null) { buf.poll() }
    }
    test("Adding item succeeds") {
        buf.append("foo")
    }
    test("Adding item makes it available for polling") {
        buf.append("foo")
        expect("foo") { buf.poll() }
    }
    test("poll() removes item from the buffer") {
        buf.append("foo")
        expect("foo") { buf.poll() }
        expect(null) { buf.poll() }
    }
    test("preserves item order") {
        (0..1000).forEach { buf.append(it.toString()) }
        expect((0..1000).toList()) { (0..1000).map { buf.poll()!!.toInt() } }
    }
}

class FIFOBufferTest : DynaTest({
    group("in-memory tests") {
        fifoBufferTestBattery {
            val map: UtilityMap<Long, String> = InMemoryUMN(
                AutoTypeResolver,
                JavaDocumentSerializer
            ).openUtilityMap("Foo")
            FIFOBuffer(map)
        }
    }

    group("mvstore tests") {
        lateinit var umn: MvstoreUMN
        beforeEach { umn = createMvstoreUMN() }
        afterEach { umn.close() }
        fifoBufferTestBattery { FIFOBuffer(umn.openUtilityMap("Foo")) }
    }
})
