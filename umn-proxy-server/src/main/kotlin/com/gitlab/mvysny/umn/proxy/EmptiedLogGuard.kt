package com.gitlab.mvysny.umn.proxy

import org.h2.mvstore.DataUtils
import org.h2.mvstore.MVStore
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.file.StandardOpenOption.READ

/**
 * Refuses a log that H2 would open empty although it holds committed chunks, before anything opens it read-write:
 *
 * ```
 * EmptiedLogGuard.check(File(dbdir, "umn_proxy_log"))   // before StartupCompaction and the store
 * ```
 *
 * A kill that cuts short a chunk at the end of the file leaves such a log whenever the store header names no chunk,
 * as after every [StartupCompaction] until H2 first reuses space. Opened read-write, the store starts empty, and its
 * `close()` truncates the file to its headers. See `R_mvstore_kill_opens_empty`.
 *
 * ## Implementation notes
 *
 * The chunks are found as `MVStoreTool.rollback()` finds them: a block starting with a `chunk:` header, whose footer
 * at the chunk's end checksums. H2's own `MVStoreTool -repair` reads the cut chunk past the end of the file and throws,
 * so the message names the byte to truncate at instead.
 */
internal object EmptiedLogGuard {
    /**
     * Returns when [file] is missing, opens at a version, or holds no complete chunk, as then nothing was committed.
     *
     * @throws IllegalStateException when H2 would open [file] empty although it holds complete chunks; the message
     *   names the byte that brings the log back when the file is truncated there.
     */
    fun check(file: File) {
        if (file.length() <= 2L * BLOCK) return
        val version = MVStore.Builder().fileName(file.path).readOnly().open().use { it.currentVersion }
        if (version > 0) return
        val last = lastCompleteChunk(file) ?: return
        error(
            "$file: H2 would open it empty, yet it holds complete chunks up to byte ${last.end}, version " +
                "${last.version}: a kill cut its last chunk short. Not starting, as the server would overwrite them. " +
                "Keep a copy of the file, run `truncate -s ${last.end} $file`, and start again."
        )
    }

    private class Chunk(val end: Long, val version: Long)

    /** The complete chunk ending last in [file]: the newest, as H2 wrote every chunk since the store header at the end. */
    private fun lastCompleteChunk(file: File): Chunk? = FileChannel.open(file.toPath(), READ).use { channel ->
        var last: Chunk? = null
        var pos = 2L * BLOCK
        while (pos + BLOCK <= channel.size()) {
            val chunk = readChunk(channel, pos)
            if (chunk != null) last = chunk
            pos = chunk?.end ?: (pos + BLOCK)
        }
        last
    }

    /** The chunk starting at [pos], when its header parses and its footer, within the file, checksums and agrees. */
    private fun readChunk(channel: FileChannel, pos: Long): Chunk? {
        val block = channel.readAt(pos, BLOCK)
        val newline = block.indexOf('\n'.code.toByte())
        if (newline < 0) return null
        val headerLine = String(block, 0, newline, ISO_8859_1).trim()
        if (!headerLine.startsWith("chunk:")) return null
        try {
            val header = DataUtils.parseMap(headerLine)
            val end = pos + DataUtils.readHexLong(header, "len", 0) * BLOCK
            if (end <= pos || end > channel.size() || DataUtils.readHexLong(header, "block", 0) * BLOCK != pos) return null
            val footer = parseFooter(channel.readAt(end - FOOTER, FOOTER)) ?: return null
            if (footer["chunk"] != header["chunk"]) return null
            return Chunk(end, DataUtils.readHexLong(footer, "version", 0))
        } catch (e: IllegalStateException) {
            log.debug("$pos: not a chunk", e)
            return null
        }
    }

    /** A chunk footer, `chunk:…,block:…,version:…,fletcher:…`, when its Fletcher-32 checksum holds. */
    private fun parseFooter(bytes: ByteArray): Map<String, String>? {
        val line = String(bytes, ISO_8859_1).trimEnd()
        val at = line.lastIndexOf(",fletcher:")
        if (at < 0) return null
        val checksum = line.substring(at + ",fletcher:".length).toLongOrNull(16)?.toInt() ?: return null
        return if (checksum == DataUtils.getFletcher32(bytes, 0, at)) DataUtils.parseMap(line.substring(0, at)) else null
    }

    private fun FileChannel.readAt(pos: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        while (buffer.hasRemaining()) {
            if (read(buffer, pos + buffer.position()) < 0) break
        }
        return buffer.array().copyOf(buffer.position())
    }

    /** H2's `MVStore.BLOCK_SIZE` and `Chunk.FOOTER_LENGTH`, both package-private. */
    private const val BLOCK = 4096
    private const val FOOTER = 128

    @JvmStatic
    private val log = LoggerFactory.getLogger(EmptiedLogGuard::class.java)
}
