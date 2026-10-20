package com.gitlab.mvysny.umn.sync

import org.junit.jupiter.api.Test
import kotlin.test.expect

class PasswordHashTest {
    @Test fun `hashes are salted`() {
        expect(false) { PasswordHash.createHash("p\r\nassw0Rd!") == PasswordHash.createHash("p\r\nassw0Rd!") }
    }

    @Test fun `validates the password`() {
        (0..20).forEach { i ->
            val hash = PasswordHash.createHash("$i")
            expect(true) { PasswordHash.validatePassword("$i", hash) }
            expect(false) { PasswordHash.validatePassword("${i + 1}", hash) }
        }
    }
}
