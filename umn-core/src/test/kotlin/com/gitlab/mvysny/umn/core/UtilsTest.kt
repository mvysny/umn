package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVStore
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.expect

class UtilsTest {
    private class CountingCloseable(private val failWith: Exception? = null) : Closeable {
        var closeCount = 0
        override fun close() {
            closeCount++
            if (failWith != null) throw failWith
        }
    }

    @Test fun `putOrRemove`() {
        val map = mutableMapOf("a" to 1)
        map.putOrRemove("b", 2)
        expect(mapOf("a" to 1, "b" to 2)) { map }
        map.putOrRemove("a", null)
        expect(mapOf("b" to 2)) { map }
    }

    @Test fun `closeQuietly swallows the exception`() {
        val c = CountingCloseable(IOException("simulated"))
        c.closeQuietly()
        expect(1) { c.closeCount }
    }

    @Nested inner class TryInit {
        @Test fun `success leaves the closeable open`() {
            val c = CountingCloseable()
            assertSame(c, c.tryInit { })
            expect(0) { c.closeCount }
        }

        @Test fun `failure closes the closeable and rethrows`() {
            val c = CountingCloseable()
            val ex = assertFailsWith<IllegalStateException> { c.tryInit { throw IllegalStateException("simulated") } }
            expect("simulated") { ex.message }
            expect(1) { c.closeCount }
        }

        @Test fun `close failure is added as suppressed`() {
            val c = CountingCloseable(IOException("close failed"))
            val ex = assertFailsWith<IllegalStateException> { c.tryInit { throw IllegalStateException("simulated") } }
            expect(listOf("close failed")) { ex.suppressed.map { it.message } }
        }
    }

    @Nested inner class UpdateIfPresent {
        @Test fun `ConcurrentHashMap`() {
            val map = ConcurrentHashMap<String, String?>(mapOf("a" to "1"))
            expect("12") { map.updateIfPresent("a") { _, v -> v + "2" } }
            expect(null) { map.updateIfPresent("b") { _, v -> v + "2" } }
            expect(mapOf<String, String?>("a" to "12")) { map }
            expect(null) { map.updateIfPresent("a") { _, _ -> null } }
            expect(mapOf()) { map }
        }

        @Test fun `MVMap`() {
            MVStore.open(null).use { store ->
                val map = store.openMap<String, String?>("test")
                map["a"] = "1"
                expect("12") { map.updateIfPresent("a") { _, v -> v + "2" } }
                expect(null) { map.updateIfPresent("b") { _, v -> v + "2" } }
                expect("12") { map["a"] }
                expect(null) { map.updateIfPresent("a") { _, _ -> null } }
                expect(0) { map.size }
            }
        }
    }

    @Test fun `ThreadLocalCache caches per thread`() {
        val cached: Any by ThreadLocalCache { Any() }
        assertSame(cached, cached)
        var other: Any? = null
        thread { other = cached }.join()
        expect(false) { other === cached }
    }

    @Test fun `AutoTypeResolver fails on an unregistered type`() {
        assertFailsWith<IllegalArgumentException> { AutoTypeResolver.toClass(Short.MAX_VALUE) }
    }

    @Nested inner class Facet {
        private val storage = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer)
        private val umn: UMN = IndexingUMN(storage, setOf())

        @Test fun `finds the UMN in the chain`() {
            assertSame(umn, umn.facet<IndexingUMN>())
            assertSame(storage, umn.facet<InMemoryUMN>())
        }

        @Test fun `fails when the chain has no such UMN`() {
            assertFailsWith<IllegalArgumentException> { umn.facet<MvstoreUMN>() }
        }
    }
}
