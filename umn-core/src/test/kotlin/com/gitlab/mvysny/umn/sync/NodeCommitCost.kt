package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.*
import org.h2.mvstore.MVStore
import org.h2.mvstore.MVStoreTool
import java.io.File
import java.io.Serializable
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Measures a commit after each local edit on a node's store, opened as Aedict opens it: `compress()`, H2's default
 * `autoCommitBufferSize`, two indices, synced; `R_mvstore_commit_per_write`. Runs the edits once per [Mode]. Args: the
 * directory for the store files, documents, edits, idle seconds after the edits, milliseconds between edits, and an
 * `autoCommitBufferSize` in KB, 0 for H2's default:
 *
 * ```
 * java -cp <umn-core's test runtime classpath> com.gitlab.mvysny.umn.sync.NodeCommitCost /tmp 30000 1000 0 0
 * ```
 *
 * A plain `main()`, so it runs on Android too: dex umn-core's main classes, this class and `Note`, kotlin-stdlib,
 * h2-mvstore and slf4j-api with `d8 --release --min-api 22 --lib android.jar --output ncc.jar …`, push `ncc.jar` to
 * `/data/local/tmp`, and run `dalvikvm -Xmx256m -cp ncc.jar com.gitlab.mvysny.umn.sync.NodeCommitCost /data/local/tmp/ncc …`
 * in `adb shell`.
 */
object NodeCommitCost {
    data class Note(val category: String, val position: Int, val text: String) : Serializable

    private val byCategory = IndexingUMN.IndexDef.simple<CompositeKey, Note>("category",
        { setOf(CompositeKey.of(it.category, it.position)) }, Note::class)
    private val byWord = IndexingUMN.IndexDef.simple<CompositeKey, Note>("word",
        { note -> note.text.split(' ').take(2).map { CompositeKey.of(it, note.category) }.toSet() }, Note::class)

    @JvmStatic fun main(args: Array<String>) {
        val dir = File(args.getOrElse(0) { System.getProperty("java.io.tmpdir") })
        val documents = args.getOrElse(1) { "30000" }.toInt()
        val edits = args.getOrElse(2) { "1000" }.toInt()
        val idleSeconds = args.getOrElse(3) { "0" }.toInt()
        val pauseMillis = args.getOrElse(4) { "0" }.toLong()
        bufferKb = args.getOrElse(5) { "0" }.toInt()
        for (mode in Mode.entries) run(File(dir, "node-commit-$mode.db"), documents, edits, idleSeconds, pauseMillis, mode)
    }

    /**
     * What follows each edit: nothing, as in a [batch]; a commit, as a [SyncingUMN] change makes by itself; or a commit
     * requested from a background thread, which edits made while one runs share.
     */
    enum class Mode { NONE, SYNC, BACKGROUND }

    private var bufferKb = 0

    private fun open(file: File): MVStore = MVStore.Builder().compress().fileName(file.path)
        .apply { if (bufferKb > 0) autoCommitBufferSize(bufferKb) }.open()

    private fun run(file: File, documents: Int, edits: Int, idleSeconds: Int, pauseMillis: Long, mode: Mode) {
        val random = Random(1)
        val words = List(500) { List(2 + random.nextInt(4)) { 'a' + random.nextInt(26) }.joinToString("") }
        fun note(position: Int) = Note("category${random.nextInt(40)}", position, List(20) { words.random(random) }.joinToString(" "))
        val ids = List(documents) { UUID(random.nextLong(), random.nextLong()) }.sorted()

        // a synced node's store at its live size: its documents, its indices built, compacted, its outgoing buffer empty
        val raw = File(file.path + ".raw").apply { delete() }
        open(raw).let { store ->
            val bare = MvstoreUMN(store, JavaDocumentSerializer, AutoTypeResolver)
            ids.forEachIndexed { i, id -> bare.create(note(i), id) }
            store.commit()
            IndexingUMN(bare, setOf(byCategory, byWord)).syncing({ error("never started") }, JavaDocumentSerializer).close()
        }
        file.delete()
        MVStoreTool.compact(raw.path, file.path, true)
        raw.delete()

        val store = open(file)
        val node = IndexingUMN(MvstoreUMN(store, JavaDocumentSerializer, AutoTypeResolver), setOf(byCategory, byWord))
            .syncing({ error("never started") }, JavaDocumentSerializer)
        val before = file.length()

        val editNanos = LongArray(edits)
        val commitNanos = Collections.synchronizedList(mutableListOf<Long>())
        val committer = Executors.newSingleThreadExecutor()
        val pending = AtomicBoolean()
        fun commit() {
            val t = System.nanoTime()
            store.commit()
            commitNanos.add(System.nanoTime() - t)
        }
        val start = System.nanoTime()
        // the node commits each edit by itself; in one batch, only the mode does
        node.batch {
            repeat(edits) { i ->
                val id = ids.random(random)
                val doc = note(i)
                val t0 = System.nanoTime()
                node.update(id, doc)
                when (mode) {
                    Mode.NONE -> {}
                    Mode.SYNC -> commit()
                    // cleared before the commit: an edit made after it either lands in this commit or requests the next
                    Mode.BACKGROUND -> if (pending.compareAndSet(false, true)) committer.execute { pending.set(false); commit() }
                }
                editNanos[i] = System.nanoTime() - t0
                if (pauseMillis > 0) Thread.sleep(pauseMillis)
            }
            committer.shutdown()
            committer.awaitTermination(1, TimeUnit.MINUTES)
        }
        val totalMillis = (System.nanoTime() - start) / 1_000_000
        val after = file.length()
        editNanos.sort()
        val commits = commitNanos.sorted().toLongArray()
        fun LongArray.at(q: Double): Long = this[(size * q).toInt().coerceAtMost(size - 1)] / 1000
        println("$mode, buffer ${store.autoCommitMemory / 1024} KB, $documents documents, $edits edits $pauseMillis ms apart, in $totalMillis ms")
        println("  edit µs, the commit included unless in the background: median ${editNanos.at(0.5)}, p90 ${editNanos.at(0.9)}, max ${editNanos.last() / 1000}")
        if (commits.isNotEmpty()) println("  ${commits.size} commits, µs: median ${commits.at(0.5)}, p90 ${commits.at(0.9)}, max ${commits.last() / 1000}")
        println("  file: ${before / 1024} KB before, ${after / 1024} KB after, ${(after - before) / edits} B an edit")
        if (idleSeconds > 0) {
            Thread.sleep(idleSeconds * 1000L)
            println("  file after $idleSeconds s idle: ${file.length() / 1024} KB")
        }
        node.close()
        println("  file closed: ${file.length() / 1024} KB")
    }
}
