package com.gitlab.mvysny.umn.core.fulltext

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.expect

class UtilityMapJoinerTest {
    @Nested inner class Mergejoin {
        @Test fun `ranges`() {
            expect(listOf(5)) { mergeJoin(listOf((0..5).iterator(), (5..10).iterator())).toList() }
            expect(listOf(5)) { mergeJoin(listOf((0..5).iterator(), (5..10).iterator(), (5..7).iterator())).toList() }
            expect(listOf(0, 1, 2, 3, 4, 5)) { mergeJoin(listOf((0..5).iterator(), (0..10).iterator())).toList() }
            expect(listOf()) { mergeJoin(listOf((0..5).iterator(), (6..10).iterator())).toList() }
            expect(listOf(6, 7, 8)) { mergeJoin(listOf((0..10).iterator(), (6..8).iterator())).toList() }
        }

        @Test fun `disjoint lists`() {
            expect(listOf()) { mergeJoin(listOf(listOf(0, 2, 4, 6).iterator(), listOf(1, 3, 5, 7).iterator())).toList() }
        }
    }
}
