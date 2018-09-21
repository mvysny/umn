package com.gitlab.mvysny.umn.sync

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.DynaTest
import com.gitlab.mvysny.umn.core.*
import kotlin.test.expect

internal fun DynaNodeGroup.ackFifoBufferTestBattery(bufferProducer: () -> AckFIFOBuffer<String>) {
    lateinit var buf: AckFIFOBuffer<String>
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
        expect(null) { buf.poll() }
    }
    test("items won't disappear unless acked") {
        buf.append("foo")
        expect("foo") { buf.poll() }
        expect(null) { buf.poll() }
        buf = bufferProducer()
        expect("foo") { buf.poll() }
        expect(null) { buf.poll() }
    }
    test("acking makes the item disappear") {
        buf.append("foo")
        expect("foo") { buf.poll() }
        expect(null) { buf.poll() }
        buf.ackLowest()
        buf = bufferProducer()
        expect(null) { buf.poll() }
    }
    test("adding item after poll() returns null will return that item") {
        buf.append("foo")
        expect("foo") { buf.poll() }
        expect(null) { buf.poll() }
        expect(null) { buf.poll() }
        expect(null) { buf.poll() }
        buf.append("bar")
        expect("bar") { buf.poll() }
        expect(null) { buf.poll() }
        expect(null) { buf.poll() }
        expect(null) { buf.poll() }
    }
    test("ack won't make the items reappear") {
        buf.append("foo")
        buf.append("bar")
        expect("foo") { buf.poll() }
        expect("bar") { buf.poll() }
        buf.ackLowest()
        expect(null) { buf.poll() }
        buf.append("baz")
        expect("baz") { buf.poll() }
        expect(null) { buf.poll() }
    }
    test("acking makes the fifo return the next item") {
        buf.append("foo")
        expect("foo") { buf.poll() }
        expect(null) { buf.poll() }
        buf.append("bar")
        expect("bar") { buf.poll() }
        expect(null) { buf.poll() }
        buf.ackLowest()
        expect(null) { buf.poll() }
        buf = bufferProducer()
        expect("bar") { buf.poll() }
        expect(null) { buf.poll() }
    }
    test("ack does nothing if there are no items") {
        buf.ackLowest()
        buf.append("foo")
        expect("foo") { buf.poll() }
        expect(null) { buf.poll() }
    }
}

class AckFIFOBufferTest : DynaTest({
    group("in-memory tests") {
        lateinit var umn: InMemoryUMN
        beforeEach { umn = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer) }
        afterEach { umn.close() }
        ackFifoBufferTestBattery {
            AckFIFOBuffer(umn.openUtilityMap("Foo"))
        }
    }

    group("mvstore tests") {
        lateinit var umn: MvstoreUMN
        beforeEach { umn = createMvstoreUMN() }
        afterEach { umn.close() }
        ackFifoBufferTestBattery { AckFIFOBuffer(umn.openUtilityMap("Foo")) }
    }
})
