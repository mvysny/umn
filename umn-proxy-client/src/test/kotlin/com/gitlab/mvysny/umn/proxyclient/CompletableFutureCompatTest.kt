package com.gitlab.mvysny.umn.proxyclient

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertFailsWith
import kotlin.test.expect

class CompletableFutureCompatTest {
    @Nested inner class BasicNonBlockingTest {
        @Test fun `test initial state`() {
            val f = CompletableFutureCompat<String>()
            expect(false) { f.isCancelled }
            expect(false) { f.isDone }
            assertFailsWith<TimeoutException> { f.get(0, TimeUnit.DAYS) }
        }

        @Test fun `test completed state`() {
            val f = CompletableFutureCompat<String>()
            expect(true) { f.complete("foo") }
            expect(false) { f.isCancelled }
            expect(true) { f.isDone }
            expect("foo") { f.get(1, TimeUnit.DAYS) }
            expect("foo") { f.get() }
        }

        @Test fun `test completed state cannot be canceled`() {
            val f = CompletableFutureCompat<String>()
            expect(true) { f.complete("foo") }
            expect(false) { f.cancel(true) }
            expect(false) { f.isCancelled }
            expect(true) { f.isDone }
            expect("foo") { f.get(1, TimeUnit.DAYS) }
            expect("foo") { f.get() }
        }

        @Test fun `follow-up completions are ignored`() {
            val f = CompletableFutureCompat<String>()
            expect(true) { f.complete("foo") }
            expect(false) { f.complete("bar") }
            expect(false) { f.isCancelled }
            expect(true) { f.isDone }
            expect("foo") { f.get(1, TimeUnit.DAYS) }
            expect("foo") { f.get() }
        }

        @Test fun `test canceled state cannot be completed`() {
            val f = CompletableFutureCompat<String>()
            expect(true) { f.cancel(true) }
            expect(false) { f.complete("foo") }
            expect(true) { f.isCancelled }
            expect(true) { f.isDone }
            assertFailsWith<CancellationException> { f.get(1, TimeUnit.DAYS) }
            assertFailsWith<CancellationException> { f.get() }
        }

        @Test fun `complete exceptionally`() {
            val f = CompletableFutureCompat<String>()
            f.completeExceptionally(RuntimeException("simulated"))
            expect(false) { f.cancel(true) }
            expect(false) { f.complete("foo") }
            expect(false) { f.isCancelled }
            expect(true) { f.isDone }
            assertFailsWith<ExecutionException> { f.get(1, TimeUnit.DAYS) }
            assertFailsWith<ExecutionException> { f.get() }
        }
    }
}
