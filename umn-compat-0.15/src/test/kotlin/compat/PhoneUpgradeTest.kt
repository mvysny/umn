package compat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * B: Android's node file, written by 0.15, opened by 0.16 and never downgraded, against the unchanged 0.12 server.
 * A fresh 0.15 node, Aedict-Online's, checks that what the phone pushed reads back the same.
 */
@Testcontainers(disabledWithoutDocker = true)
class PhoneUpgradeTest {
    @TempDir
    lateinit var dir: Path
    private val account = Account()
    private val phone get() = account.node(dir, "phone.db")
    private val web get() = account.node(dir, "web.db")

    @Test
    fun `B1 synced state carries over, the sync pointer too`() {
        val before = phone.on015("cat=Work", "item=Work/apple", "item=Work/pear", "cat=Home", "item=Home/chair", "sync", "dump")
        val upgraded = phone.onHead("dump")
        assertEquals("opened queued=0 pointer=5", upgraded.first())
        assertEquals(before.docs(), upgraded.docs())

        // the indices 0.15 built find the documents 0.16 edits
        val edited = phone.onHead("item=Work/plum", "edit=chair/stool", "sync", "dump")
        assertEquals(7, edited.number("log"))
        assertEquals(setOf("cat Work", "item Work/apple", "item Work/pear", "item Work/plum", "cat Home", "item Home/stool"), edited.contents())
        assertEquals(edited.docs(), web.on015("sync", "dump").docs())
    }

    @Test
    fun `B2 the offline queue 0_15 left is pushed exactly once`() {
        phone.on015("cat=Work", "item=Work/apple", "sync")
        phone.on015("item=Work/pear", "edit=apple/apple2", "rename=Work/Job", "cat=Home", "del=pear")

        val upgraded = phone.onHead("sync", "dump")
        assertEquals("opened queued=5 pointer=2", upgraded.first())
        assertEquals(7, upgraded.number("log"))
        assertEquals(setOf("cat Job", "item Job/apple2", "cat Home"), upgraded.contents())
        assertEquals(upgraded.docs(), web.on015("sync", "dump").docs())
    }

    @Test
    fun `B3 a phone that never synced turns sync on under 0_16`() {
        phone.on015("plain", "cat=Work", "item=Work/apple", "item=Work/pear")

        // 0.16 queues a DocumentCreated2 per document the file already had
        val upgraded = phone.onHead("sync", "dump")
        assertEquals("opened queued=3 pointer=0", upgraded.first())
        assertEquals(3, upgraded.number("log"))
        assertEquals(upgraded.docs(), web.on015("sync", "dump").docs())
    }

    @Test
    fun `B4 0_16 keeps working on the file it upgraded`() {
        phone.on015("cat=Work", "item=Work/apple", "sync")
        phone.onHead("item=Work/pear", "sync")
        phone.onHead("edit=pear/plum", "sync")

        val third = phone.onHead("dump")
        assertEquals("opened queued=0 pointer=4", third.first())
        assertEquals(setOf("cat Work", "item Work/apple", "item Work/plum"), third.contents())
        assertEquals(4, account.logSize)
    }
}
