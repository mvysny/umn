package com.gitlab.mvysny.umn.core

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.expect

class PagedListTest {
    private val loaded = mutableListOf<IntRange>()
    private fun list(size: Int = 10, source: Int = size) =
        PagedList(size, 3, null) { range -> loaded += range; range.filter { it < source } }

    @Test fun `loads a page when first read, then keeps it`() {
        val list = list()
        expect(listOf()) { loaded }
        expect(4) { list[4] }
        expect(5) { list[5] }
        expect(listOf(3..5)) { loaded }
        expect(9) { list[9] }
        expect(listOf(3..5, 9..9)) { loaded }
    }

    @Test fun `keeps the page read last and its neighbours`() {
        val list = list()
        list[0]; list[3]; list[6]
        list[0]
        expect(listOf(0..2, 3..5, 6..8, 0..2)) { loaded }
    }

    @Test fun `throws past the size, or where the source has shrunk`() {
        val list = list(source = 5)
        assertFailsWith<IndexOutOfBoundsException> { list[10] }
        assertFailsWith<IndexOutOfBoundsException> { list[-1] }
        expect(4) { list[4] }
        assertFailsWith<IndexOutOfBoundsException> { list[5] }
    }

    @Test fun `rejects a page size below 1`() {
        assertFailsWith<IllegalArgumentException> { PagedList(1, 0, null) { listOf(1) } }
    }
}
