package com.gitlab.mvysny.umn.proxyclient

import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectThrows
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.expect

class CompletableFutureCompatTest : DynaTest({
    group("basic non-blocking test") {
        test("test initial state") {
            val f = CompletableFutureCompat<String>()
            expect(false) { f.isCancelled }
            expect(false) { f.isDone }
            expectThrows(TimeoutException::class) { f.get(0, TimeUnit.DAYS) }
        }

        test("test completed state") {
            val f = CompletableFutureCompat<String>()
            expect(true) { f.complete("foo") }
            expect(false) { f.isCancelled }
            expect(true) { f.isDone }
            expect("foo") { f.get(1, TimeUnit.DAYS) }
            expect("foo") { f.get() }
        }

        test("test completed state cannot be canceled") {
            val f = CompletableFutureCompat<String>()
            expect(true) { f.complete("foo") }
            expect(false) { f.cancel(true) }
            expect(false) { f.isCancelled }
            expect(true) { f.isDone }
            expect("foo") { f.get(1, TimeUnit.DAYS) }
            expect("foo") { f.get() }
        }

        test("follow-up completions are ignored") {
            val f = CompletableFutureCompat<String>()
            expect(true) { f.complete("foo") }
            expect(false) { f.complete("bar") }
            expect(false) { f.isCancelled }
            expect(true) { f.isDone }
            expect("foo") { f.get(1, TimeUnit.DAYS) }
            expect("foo") { f.get() }
        }

        test("test canceled state cannot be completed") {
            val f = CompletableFutureCompat<String>()
            expect(true) { f.cancel(true) }
            expect(false) { f.complete("foo") }
            expect(true) { f.isCancelled }
            expect(true) { f.isDone }
            expectThrows(CancellationException::class) { f.get(1, TimeUnit.DAYS) }
            expectThrows(CancellationException::class) { f.get() }
        }

        test("complete exceptionally") {
            val f = CompletableFutureCompat<String>()
            f.completeExceptionally(RuntimeException("simulated"))
            expect(false) { f.cancel(true) }
            expect(false) { f.complete("foo") }
            expect(false) { f.isCancelled }
            expect(true) { f.isDone }
            expectThrows(ExecutionException::class) { f.get(1, TimeUnit.DAYS) }
            expectThrows(ExecutionException::class) { f.get() }
        }
    }
})
