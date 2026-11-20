package compat

import com.gitlab.mvysny.umn.core.MvstoreUMN
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pages H2 1.4.197 leaked into a phone's file (`R_mvstore_197_meta_leak`), which 0.15 wrote, dropped by HEAD before
 * it opens the file. No server: the node never synced.
 */
class LeakedPagesTest {
    @TempDir
    lateinit var dir: Path
    private val file get() = dir.resolve("phone.db")

    private fun session(vararg ops: String): Array<String> =
        arrayOf("session", file.toString(), "ws://unused", "http://unused", "user", "pass", "plain", *ops)

    @Test
    fun `the pages 0_15 leaked are dropped, the documents and indices kept`() {
        // what made 1.4.197 leak: 12 maps, as Aedict's file holds, and sessions of edits, each idling before its close
        Umn015.run(*session("cat=Work", "maps=8"))
        repeat(8) { Umn015.run(*session("items=Work/20", "idle=1500")) }
        val before = Umn015.run(*session("dump"))

        assertTrue(MvstoreUMN.dropLeakedPages(file.toFile()) > 0, "0.15 leaked no page")
        // the session checks every index at open and at close
        assertEquals(before, Head.run(*session("dump")))
        assertEquals(0, MvstoreUMN.dropLeakedPages(file.toFile()))
    }
}
