package compat

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.fail

/**
 * Runs [Steps] on HEAD, in this JVM; returns the lines they print.
 */
object Head {
    fun run(vararg args: String): List<String> = mutableListOf<String>().also { out -> Steps.run({ out.add(it) }, *args) }
}

/**
 * Runs [Steps] on UMN 0.15, in a child JVM on Aedict-Online's classpath; returns the lines they print.
 */
object Umn015 {
    private val classpath: String = System.getProperty("umn015.classpath")
        ?: error("umn015.classpath is not set; run the tests through Gradle")
    private val java: String = ProcessHandle.current().info().command().get()

    fun run(vararg args: String): List<String> = start(*args).await()

    /**
     * Starts the steps without waiting, for a node that must stay connected while the test acts.
     */
    fun start(vararg args: String): Running =
        Running(ProcessBuilder(java, "-cp", classpath, "compat.Driver015", *args).start(), args.toList())

    class Running(private val process: Process, private val args: List<String>) {
        private val lines = CopyOnWriteArrayList<String>()
        private val stderr = StringBuffer()
        private val readers = listOf(
            thread { process.inputStream.bufferedReader().forEachLine { lines.add(it) } },
            thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } },
        )

        fun awaitLine(line: String) {
            val deadline = System.currentTimeMillis() + 60_000
            while (line !in lines) {
                if (!process.isAlive || System.currentTimeMillis() > deadline) {
                    await()
                    fail("0.15 never printed $line\n${report()}")
                }
                Thread.sleep(50)
            }
        }

        fun await(): List<String> {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("0.15 timed out\n${report()}")
            }
            readers.forEach { it.join() }
            if (process.exitValue() != 0) fail("0.15 failed\n${report()}")
            return lines.toList()
        }

        private fun report() = "args: $args\nstdout:\n${lines.joinToString("\n")}\nstderr:\n$stderr"
    }
}

/**
 * One node file of an [account]; each call opens it on 0.15 or on HEAD, runs a [Steps] session's ops, and closes it.
 *
 * ```
 * phone.on015("cat=Work", "item=Work/apple", "sync")
 * phone.onHead("dump").contents()   // [cat Work, item Work/apple]
 * ```
 */
class NodeFile(private val account: Account, private val file: Path) {
    fun on015(vararg ops: String): List<String> = Umn015.run(*session(ops))
    fun onHead(vararg ops: String): List<String> = Head.run(*session(ops))
    fun start015(vararg ops: String): Umn015.Running = Umn015.start(*session(ops))

    private fun session(ops: Array<out String>): Array<String> =
        arrayOf("session", file.toString(), account.server.wsUrl, account.server.adminUrl, account.user, account.pass, *ops)
}

/**
 * The `doc` lines of a session's `dump`.
 */
fun List<String>.docs(): List<String> = filter { it.startsWith("doc ") }

/**
 * The dumped documents without their ids: `cat Work`, `item Work/apple`.
 */
fun List<String>.contents(): Set<String> =
    docs().map { it.split(' ', limit = 4).let { (_, kind, _, rest) -> "$kind $rest" } }.toSet()

/**
 * A number a session printed as `key=<n>`, from the first line that has it.
 */
fun List<String>.number(key: String): Long =
    firstNotNullOfOrNull { Regex("\\b$key=(\\d+)").find(it)?.groupValues?.get(1)?.toLong() }
        ?: fail("no $key= in $this")
