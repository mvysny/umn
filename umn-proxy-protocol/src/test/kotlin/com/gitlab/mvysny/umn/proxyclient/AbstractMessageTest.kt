package com.gitlab.mvysny.umn.proxyclient

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.expect

class AbstractMessageTest {
    private fun AbstractMessage.roundTrip(): AbstractMessage = AbstractMessage.fromByteArray(asByteArray())

    @Test fun `toString shows the type`() {
        expect("Started") { AbstractMessage.Started.toString() }
    }

    @Test fun `serialization round-trip`() {
        assertSame(AbstractMessage.Started, AbstractMessage.Started.roundTrip())
        assertSame(AbstractMessage.OnAdded, AbstractMessage.OnAdded.roundTrip())
        expect(listOf<Byte>(1, 2, 3)) { assertIs<AbstractMessage.AddAsync>(AbstractMessage.AddAsync(byteArrayOf(1, 2, 3)).roundTrip()).record.toList() }
        expect(listOf<Byte>(4, 5)) { assertIs<AbstractMessage.OnLogRecord>(AbstractMessage.OnLogRecord(byteArrayOf(4, 5)).roundTrip()).record!!.toList() }
        expect(null) { assertIs<AbstractMessage.OnLogRecord>(AbstractMessage.OnLogRecord(null).roundTrip()).record }
    }

    @Test fun `invalid messages fail to deserialize`() {
        assertFailsWith<IllegalArgumentException> { AbstractMessage.fromByteArray(byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { AbstractMessage.fromByteArray(byteArrayOf(25)) }
    }
}
