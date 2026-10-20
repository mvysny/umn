package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVStore
import java.io.File
import kotlin.test.expect

/**
 * Creates an [UMN] backed by a file-based MV Store.
 */
fun createMvstoreUMN(dbFileName: String = File.createTempFile("tmp", null).absolutePath): MvstoreUMN = MvstoreUMN(MVStore.open(dbFileName), JavaDocumentSerializer,
    AutoTypeResolver)

/**
 * Expects that a value provided by the [actual] closure eventually becomes as [expected].
 * Wait at most [maxDelay].
 */
fun <T> expectAsync(expected: T, maxDelay: Long = 3000L, actual: () -> T) {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start <= maxDelay) {
        if (expected == actual()) return
        Thread.sleep(10)
    }
    expect(expected, actual)
}
