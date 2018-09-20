package com.gitlab.mvysny.umn.proxyclient

import java.util.concurrent.*

/**
 * A backport of Java `CompletableFuture` which works with old Androids.
 */
class CompletableFutureCompat<V> : Future<V> {
    private sealed class Result<out V> {
        abstract val value: V
        class Ok<V>(override val value: V) : Result<V>()
        class Error(val e: Throwable) : Result<Nothing>() {
            override val value: Nothing
                get() = throw ExecutionException(e)
        }
        object Cancel : Result<Nothing>() {
            override val value: Nothing
                get() = throw CancellationException()
        }
    }
    private val completion = LinkedBlockingQueue<Result<V>>(1)
    /**
     * If not already completed, causes invocations of [get]
     * and related methods to throw the given exception.
     *
     * @param ex the exception
     * @return `true` if this invocation caused this CompletableFuture
     * to transition to a completed state, else `false`
     */
    fun completeExceptionally(ex: Throwable): Boolean = completion.offer(Result.Error(ex))

    /**
     * If not already completed, completes this CompletableFuture with
     * a [CancellationException].
     *
     * @param mayInterruptIfRunning this value has no effect in this
     * implementation because interrupts are not used to control
     * processing.
     *
     * @return `true` if this task is now cancelled
     */
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        val canceled = completion.offer(Result.Cancel)
        return canceled || isCancelled
    }

    /**
     * If not already completed, sets the value returned by [get] and related methods to the given value.
     *
     * @param value the result value
     * @return `true` if this invocation caused this CompletableFuture
     * to transition to a completed state, else `false`
     */
    fun complete(value: V): Boolean = completion.offer(Result.Ok(value))

    override fun isDone(): Boolean = completion.isNotEmpty()

    override fun get(): V = completion.take().value

    override fun get(timeout: Long, unit: TimeUnit): V {
        val result = completion.poll(timeout, unit) ?: throw TimeoutException()
        return result.value
    }

    override fun isCancelled(): Boolean = completion.poll() == Result.Cancel
}
