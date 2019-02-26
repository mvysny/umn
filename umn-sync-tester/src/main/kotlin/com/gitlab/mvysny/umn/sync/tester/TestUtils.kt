package com.gitlab.mvysny.umn.sync.tester

import com.gitlab.mvysny.umn.core.*
import com.gitlab.mvysny.umn.sync.GlobalLogClient
import org.h2.mvstore.MVStore
import java.util.*
import kotlin.test.expect

/**
 * Creates an [UMN] backed by a file-based MV Store.
 */
fun createMvstoreUMN(): MvstoreUMN = MvstoreUMN(
    MVStore.open(createTempFile().absolutePath), JavaDocumentSerializer,
    AutoTypeResolver)

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

fun GlobalLogClient.addLogCreated(doc: Any) {
    val log = LogRecord.DocumentCreated(doc, AutoTypeResolver.toType(doc.javaClass.kotlin), UUID.randomUUID())
    addAsync(log)
}
fun GlobalLogClient.addAsync(log: LogRecord) = addAsync(log.toByteArray(JavaDocumentSerializer))
