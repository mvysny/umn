package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.expect

/**
 * Tests an [AckFIFOBuffer] stored in a [UMN]; run it like [GenericUmnTestBattery].
 */
abstract class AckFifoBufferTestBattery {
    /**
     * Returns a new empty storage.
     */
    abstract fun newStorage(): UMN

    private lateinit var umn: UMN
    private lateinit var buf: AckFIFOBuffer<String>
    @BeforeEach fun createBuffer() {
        umn = newStorage()
        buf = openBuffer()
    }
    @AfterEach fun closeStorage() { umn.close() }

    /**
     * Reopens the buffer over the same storage, to check what it persisted.
     */
    private fun openBuffer(): AckFIFOBuffer<String> = AckFIFOBuffer(umn.openUtilityMap("Foo"))

    @Test fun `Initially the buffer is empty`() {
        expect(null) { buf.poll() }
    }
    @Test fun `Adding item succeeds`() {
        buf.append("foo")
    }
    @Test fun `Adding item makes it available for polling`() {
        buf.append("foo")
        expect("foo") { buf.poll() }
    }
    @Test fun `poll() removes item from the buffer`() {
        buf.append("foo")
        expect("foo") { buf.poll() }
        expect(null) { buf.poll() }
    }
    @Test fun `preserves item order`() {
        (0..1000).forEach { buf.append(it.toString()) }
        expect((0..1000).toList()) { (0..1000).map { buf.poll()!!.toInt() } }
        expect(null) { buf.poll() }
    }
    @Test fun `items won't disappear unless acked`() {
        buf.append("foo")
        expect("foo") { buf.poll() }
        expect(null) { buf.poll() }
        buf = openBuffer()
        expect("foo") { buf.poll() }
        expect(null) { buf.poll() }
    }
    @Test fun `acking makes the item disappear`() {
        buf.append("foo")
        expect("foo") { buf.poll() }
        expect(null) { buf.poll() }
        buf.ackLowest()
        buf = openBuffer()
        expect(null) { buf.poll() }
    }
    @Test fun `adding item after poll() returns null will return that item`() {
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
    @Test fun `ack won't make the items reappear`() {
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
    @Test fun `acking makes the fifo return the next item`() {
        buf.append("foo")
        expect("foo") { buf.poll() }
        expect(null) { buf.poll() }
        buf.append("bar")
        expect("bar") { buf.poll() }
        expect(null) { buf.poll() }
        buf.ackLowest()
        expect(null) { buf.poll() }
        buf = openBuffer()
        expect("bar") { buf.poll() }
        expect(null) { buf.poll() }
    }
    @Test fun `ack does nothing if there are no items`() {
        buf.ackLowest()
        buf.append("foo")
        expect("foo") { buf.poll() }
        expect(null) { buf.poll() }
    }
    @Test fun `rewindPoll() hands out the unacked items again`() {
        buf.append("foo")
        buf.append("bar")
        buf.append("baz")
        expect("foo") { buf.poll() }
        expect("bar") { buf.poll() }
        buf.ackLowest()
        buf.rewindPoll()
        expect("bar") { buf.poll() }
        expect("baz") { buf.poll() }
        expect(null) { buf.poll() }
    }
    @Test fun `rewindPoll() on a fully acked buffer hands out nothing`() {
        buf.append("foo")
        expect("foo") { buf.poll() }
        buf.ackLowest()
        buf.rewindPoll()
        expect(null) { buf.poll() }
    }
    @Test fun `clear removes all items`() {
        buf.append("foo")
        buf.clear()
        expect(null) { buf.poll() }
    }
    @Test fun `clear doesn't disturb ack`() {
        buf.append("aaa")
        buf.clear()
        buf.append("foo")
        expect("foo") { buf.poll() }
        expect(null) { buf.poll() }
        buf.append("bar")
        expect("bar") { buf.poll() }
        expect(null) { buf.poll() }
        buf.ackLowest()
        expect(null) { buf.poll() }
        buf = openBuffer()
        expect("bar") { buf.poll() }
        expect(null) { buf.poll() }
    }
}

class AckFIFOBufferTest {
    @Nested inner class InMemoryTests : AckFifoBufferTestBattery() {
        override fun newStorage(): UMN = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer)
    }

    @Nested inner class MvstoreTests : AckFifoBufferTestBattery() {
        override fun newStorage(): UMN = createMvstoreUMN()
    }
}
