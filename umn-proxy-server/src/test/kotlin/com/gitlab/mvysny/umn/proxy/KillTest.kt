package com.gitlab.mvysny.umn.proxy

import com.gitlab.mvysny.umn.sync.MVStoreGlobalLog
import com.gitlab.mvysny.umn.sync.append
import org.h2.mvstore.MVStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.expect
import kotlin.test.fail

/**
 * SIGKILLs a child JVM, [KillTestChild] running [StartupCompaction] or [KillTestPusher] pushing records, at points
 * spread over its work, and reads what it left on disk: `R_kill_vs_crash`.
 */
class KillTest {
    @TempDir lateinit var dir: File

    @Test fun `a kill loses no record the proxy server ACKed or broadcast`() {
        repeat(PUSHER_KILLS) { i ->
            val dbdir = File(dir, "pusher$i").apply { mkdir() }
            val file = File(dbdir, "umn_proxy_log")
            MVStoreGlobalLog(MVStore.Builder().fileName(file.path).open()).use { it.createOrUpdateUser("user", "pwd") }
            val lines = Child(KillTestPusher::class.java, dbdir).use { child ->
                child.await("go")
                Thread.sleep(50L + 100 * i)
                child.kill()
                child.lines
            }
            val acked = lines.count { it == "acked" }
            val received = lines.count { it == "received" }
            assertTrue(acked > 0, "killed before the first ACK:\n${lines.joinToString("\n")}")
            MVStoreGlobalLog(MVStore.Builder().fileName(file.path).readOnly().open()).use { log ->
                val records = log.getLogMap("user")
                val stored = records.sizeAsLong()
                assertTrue(acked <= stored && received <= stored, "acked $acked, received $received, stored $stored")
                (0 until stored).forEach { k -> expect("$k") { String(records[k]!!) } }
                println("kill $i: acked $acked, received $received, stored $stored")
            }
        }
    }

    @Test fun `a kill during the startup compaction leaves the log whole`() {
        val original = File(dir, "original").also { writeLog(it) }
        val originalHash = original.sha256()
        val originalContents = contents(original)
        // the child's own compaction time, so that the kills land at the same fractions of it on any machine
        val millis = onCopy("calibrate") { log ->
            Child(KillTestChild::class.java, log).use { child ->
                child.await("go")
                val t0 = System.nanoTime()
                child.await("done")
                (System.nanoTime() - t0) / 1_000_000
            }
        }
        val outcomes = sortedMapOf<String, Int>()
        repeat(KILLS) { i ->
            val delay = millis * i * 3 / 2 / (KILLS - 1)  // 0 to 1.5 times the compaction: one child runs slower than another
            onCopy("$i") { log ->
                val leftover = File(log.path + ".compacting")
                Child(KillTestChild::class.java, log).use { child ->
                    child.await("go")
                    Thread.sleep(delay)
                    child.kill()
                }
                val outcome = when {
                    log.sha256() == originalHash -> when {
                        !leftover.exists() -> "before the copy"
                        contentsOrNull(leftover) == originalContents -> "before the rename"
                        else -> "mid-copy"
                    }
                    !leftover.exists() && contents(log) == originalContents -> "after the rename"
                    else -> fail("killed $delay ms in: the log is neither the original nor a whole copy")
                }
                outcomes.merge(outcome, 1, Int::plus)
                StartupCompaction.compact(log)  // the next start
                expect(originalContents) { contents(log) }
                assertFalse(leftover.exists())
            }
        }
        println("compaction: $millis ms; kills: $outcomes")
        assertTrue("mid-copy" in outcomes, "kills: $outcomes")
    }

    /** Runs [block] on a copy of `original` named `umn_proxy_log`, in a directory of its own, deleted after. */
    private fun <T> onCopy(name: String, block: (log: File) -> T): T {
        val d = File(dir, name).apply { mkdir() }
        try {
            return block(File(dir, "original").copyTo(File(d, "umn_proxy_log")))
        } finally {
            d.deleteRecursively()
        }
    }

    /** Writes a log shaped like production's, see [StartupCompactionTest]: 30 MB, 20 MB of it live. */
    private fun writeLog(file: File) {
        val db = MVStore.Builder().compress().autoCompactFillRate(0).fileName(file.path).open()
        MVStoreGlobalLog(db).use { log ->
            val users = listOf("kept1", "kept2", "leaked")
            users.forEach { log.createOrUpdateUser(it, "pwd") }
            val random = Random(42)
            repeat(10_000) { i ->
                users.forEach { log.getLogMap(it).append(random.nextBytes(1000)) }
                if (i % 20 == 0) db.commit()
            }
            db.removeMap(log.getLogMap("leaked"))
        }
    }

    /** A digest of every map's every entry: what a compaction must keep. */
    private fun contents(file: File): String =
        MVStore.Builder().fileName(file.path).readOnly().open().use { db ->
            val md = MessageDigest.getInstance("SHA-256")
            for (name in db.mapNames.sorted()) {
                md.update(name.toByteArray())
                for ((k, v) in db.openMap<Any, Any>(name).entries) {
                    md.update("$k".toByteArray())
                    md.update(if (v is ByteArray) v else "$v".toByteArray())
                }
            }
            HexFormat.of().formatHex(md.digest())
        }

    /** A half-written copy may not even open. */
    private fun contentsOrNull(file: File): String? = try {
        contents(file)
    } catch (e: IllegalStateException) {
        println("$file doesn't open: $e")
        null
    }

    private fun File.sha256(): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(readBytes()))

    /** A JVM running [main]'s `main()` on [file], on this JVM's classpath. */
    private class Child(main: Class<*>, file: File) : AutoCloseable {
        private val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp", System.getProperty("java.class.path"),
            main.name, file.path
        ).redirectErrorStream(true).start()
        /** Its output so far, every line once [kill] returns. */
        val lines: MutableList<String> = CopyOnWriteArrayList()
        private val reader = thread(isDaemon = true) { process.inputStream.bufferedReader().forEachLine { lines += it } }

        fun await(line: String) {
            val deadline = System.nanoTime() + 60_000_000_000
            while (line !in lines) {
                if (!reader.isAlive && line !in lines) fail("the child exited before '$line':\n${lines.joinToString("\n")}")
                if (System.nanoTime() > deadline) fail("no '$line' within a minute:\n${lines.joinToString("\n")}")
                Thread.sleep(1)
            }
        }

        /** SIGKILL, on Linux; returns once the child is gone and its output read. */
        fun kill() {
            process.destroyForcibly().waitFor()
            reader.join()
        }

        override fun close() {
            kill()
        }
    }

    private companion object {
        const val KILLS = 12
        const val PUSHER_KILLS = 5
    }
}

/** The child JVM [KillTest] kills: runs [StartupCompaction] on the file it's given, saying `go` before, `done` after. */
object KillTestChild {
    @JvmStatic fun main(args: Array<String>) {
        say("go")
        StartupCompaction.compact(File(args[0]))
        say("done")
    }
}

/**
 * The child JVM [KillTest] kills mid-push: opens the proxy server's log in the directory it's given, as `user`, and
 * pushes the records `"0"`, `"1"`, … until killed, saying `acked` on each ACK and `received` on each record broadcast
 * back to it.
 */
object KillTestPusher {
    @JvmStatic fun main(args: Array<String>) {
        val provider = MVStoreGlobalLogProvider(File(args[0]), compactOnStartup = false)
        val client = provider.createClient("user", "pwd") {}
        client.setAddCallback { say("acked") }
        client.setReceiverCallback(0) { say("received") }
        client.start()
        say("go")
        var i = 0
        while (true) {
            client.addAsync("${i++}".toByteArray())
            Thread.sleep(1)
        }
    }
}

private fun say(line: String) {
    println(line)
    System.out.flush()
}
