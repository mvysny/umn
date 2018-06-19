package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVStore
import kotlin.test.expect

/**
 * Creates an [UMN] backed by a file-based MV Store.
 */
fun createMvstoreUMN(): MvstoreUMN = MvstoreUMN(MVStore.open(createTempFile().absolutePath), JavaDocumentSerializer)

/**
 * Expects that a value provided by the [actual] closure eventually becomes as [expected].
 * Wait at most [maxDelay].
 */
fun <T> expectAsync(expected: T, maxDelay: Long = 300L, actual: ()->T) {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start <= maxDelay) {
        if (expected == actual()) return
        Thread.sleep(10)
    }
    expect(expected, actual)
}
