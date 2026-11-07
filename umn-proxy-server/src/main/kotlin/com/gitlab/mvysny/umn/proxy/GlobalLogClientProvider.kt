package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.sync.GlobalLogClient
import com.gitlab.mvysny.umn.sync.MVStoreGlobalLog
import org.h2.mvstore.MVStore
import java.io.Closeable
import java.io.File
import java.nio.file.Files

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
     */
    fun createClient(username: String, password: String): GlobalLogClient
}

/**
 * Creates [GlobalLogClient]s that connects to a [MVStoreGlobalLog] stored within given [dbdir] file.
 */
internal class MVStoreGlobalLogProvider(val dbdir: File) : GlobalLogClientProvider {
    override fun createClient(username: String, password: String): GlobalLogClient = mvstorelog.newClient(username, password)

    override fun close() {
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
        db = MVStore.Builder().compress().fileName(File(dbdir, "umn_proxy_log").absolutePath).open()
        mvstorelog = MVStoreGlobalLog(db)
    }

    override val activeClientCount: Int
        get() = mvstorelog.activeClientCount
}
