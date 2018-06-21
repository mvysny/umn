package com.gitlab.mvysny.umn.sync.tester

import kotlin.test.expect

/**
 * Expects that a value provided by the [actual] closure eventually becomes as [expected].
 * Wait at most [maxDelay].
 */
fun <T> expectAsync(expected: T, maxDelay: Long = 3000L, actual: ()->T) {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start <= maxDelay) {
        if (expected == actual()) return
        Thread.sleep(10)
    }
    expect(expected, actual)
}
