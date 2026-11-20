package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.MVStoreGlobalLog
import com.gitlab.mvysny.umn.sync.MVStoreGlobalLogClient
import org.h2.mvstore.MVStore
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Provides [GlobalLogClient] of particular type. Upon closing, it should also close all currently opened clients.
 */
internal interface GlobalLogClientProvider : Closeable {
    /**
     * An estimate of how many [GlobalLogClient]s are currently active.
     */
    val activeClientCount: Int

    /**
     * Creates a new [GlobalLogClient] connected with given [username] and [password].
     *
     * @param onUserDeleted invoked once when the user is deleted while the client is started, after the client is
     *   closed. It runs on the deleting thread: it must not block.
     */
    fun createClient(username: String, password: String, onUserDeleted: () -> Unit): GlobalLogClient
}

/**
 * Creates [GlobalLogClient]s that connects to a [MVStoreGlobalLog] stored within given [dbdir] file.
 *
 * The store's auto-compaction is off, and a timer calls [compact] every [compactInterval] instead; see
 * `D_own_compaction_timer`.
 *
 * @param compactOnStartup first rewrites the file with [StartupCompaction].
 * @throws IllegalStateException from [EmptiedLogGuard], on a log a kill left that H2 would open empty.
 */
internal class MVStoreGlobalLogProvider(
    val dbdir: File,
    compactInterval: Duration = Duration.ofMinutes(1),
    compactOnStartup: Boolean = true,
) : GlobalLogClientProvider {
    override fun createClient(username: String, password: String, onUserDeleted: () -> Unit): GlobalLogClient =
        (mvstorelog.newClient(username, password) as MVStoreGlobalLogClient).apply { setUserDeletedCallback(onUserDeleted) }

    /**
     * Stops the compaction timer, waiting for a running [compact] to finish, then closes the store.
     */
    override fun close() {
        // Never shutdownNow(): an interrupt inside the store's FileChannel I/O closes the channel, see
        // R_mvstore_compact_concurrency.
        compactor.shutdown()
        if (!compactor.awaitTermination(1, TimeUnit.MINUTES)) {
            log.warn("$this: compaction still running after a minute, closing the store anyway")
        }
        mvstorelog.close()
    }

    override fun toString() = "MVStoreGlobalLogProvider(dbdir=$dbdir)"

    private val db: MVStore
    val mvstorelog: MVStoreGlobalLog
    init {
        check(dbdir.isAbsolute) { "$dbdir must be absolute"}
        if (!dbdir.exists() || !dbdir.isDirectory) {
            Files.createDirectory(dbdir.toPath())
        }
        val file = File(dbdir, "umn_proxy_log")
        EmptiedLogGuard.check(file)
        if (compactOnStartup) StartupCompaction.compact(file)
        db = MVStore.Builder().compress().autoCompactFillRate(0).fileName(file.absolutePath).open()
        mvstorelog = MVStoreGlobalLog(db)
    }

    private val compactor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "umn-proxy-log-compactor").apply { isDaemon = true }
    }
    init {
        val millis = compactInterval.toMillis()
        compactor.scheduleWithFixedDelay(::compact, millis, millis, TimeUnit.MILLISECONDS)
    }

    /**
     * Rewrites the live pages of the emptiest chunks while the store is under 40 % full, up to the store's
     * auto-commit memory per call: one round of MVStore's own auto-compaction. Safe beside writers.
     *
     * @return true if it rewrote anything; false also when it failed, which it logs.
     */
    fun compact(): Boolean = try {
        db.compact(COMPACT_FILL_RATE, db.autoCommitMemory)
    } catch (e: Exception) {
        // Swallowed: a throw would cancel the timer, and every later compaction with it.
        log.warn("$this: compaction failed", e)
        false
    }

    override val activeClientCount: Int
        get() = mvstorelog.activeClientCount

    private companion object {
        /** MVStore's own `autoCompactFillRate` default. */
        private const val COMPACT_FILL_RATE = 40

        @JvmStatic
        private val log = LoggerFactory.getLogger(MVStoreGlobalLogProvider::class.java)
    }
}
