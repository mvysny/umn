package com.gitlab.mvysny.umn.proxyclient

import org.junit.jupiter.api.Test
import kotlin.test.expect

class AbstractMessageTest {
    @Test fun `toString`() {
        expect("Started") { AbstractMessage.Started.toString() }
    }
}
