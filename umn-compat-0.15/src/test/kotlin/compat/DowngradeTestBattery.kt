package compat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * F: a node file 0.16 wrote, opened by 0.15 — B run backwards. Aedict never downgrades the phone, but
 * `D_frozen_0_15_formats` promises it. A fresh 0.16 node (`web`) checks that what 0.15 pushed reads back the same.
 */
abstract class DowngradeTestBattery(server: ProxyServer) {
    @TempDir
    lateinit var dir: Path
    private val account = Account(server)
    private val phone get() = account.node(dir, "phone.db")
    private val web get() = account.node(dir, "web.db")

    @Test
    fun `F1 synced state carries over, the sync pointer too`() {
        val before = phone.onHead("cat=Work", "item=Work/apple", "item=Work/pear", "cat=Home", "item=Home/chair", "sync", "dump")
        val downgraded = phone.on015("dump")
        assertEquals("opened queued=0 pointer=5", downgraded.first())
        assertEquals(before.docs(), downgraded.docs())

        // the indices 0.16 built find the documents 0.15 edits
        val edited = phone.on015("item=Work/plum", "edit=chair/stool", "sync", "dump")
        assertEquals(7, edited.number("log"))
        assertEquals(setOf("cat Work", "item Work/apple", "item Work/pear", "item Work/plum", "cat Home", "item Home/stool"), edited.contents())
        assertEquals(edited.docs(), web.onHead("sync", "dump").docs())
    }

    @Test
    fun `F2 the offline queue 0_16 left is pushed exactly once`() {
        phone.onHead("cat=Work", "item=Work/apple", "sync")
        phone.onHead("item=Work/pear", "edit=apple/apple2", "rename=Work/Job", "cat=Home", "del=pear")

        val downgraded = phone.on015("sync", "dump")
        assertEquals("opened queued=5 pointer=2", downgraded.first())
        assertEquals(7, downgraded.number("log"))
        assertEquals(setOf("cat Job", "item Job/apple2", "cat Home"), downgraded.contents())
        assertEquals(downgraded.docs(), web.onHead("sync", "dump").docs())
    }

    @Test
    fun `F3 a phone that never synced turns sync on under 0_15`() {
        phone.onHead("plain", "cat=Work", "item=Work/apple", "item=Work/pear")

        val downgraded = phone.on015("sync", "dump")
        assertEquals("opened queued=3 pointer=0", downgraded.first())
        assertEquals(3, downgraded.number("log"))
        assertEquals(downgraded.docs(), web.onHead("sync", "dump").docs())
    }

    @Test
    fun `F4 up, down and up again, each version editing the file in turn`() {
        phone.on015("cat=Work", "item=Work/apple", "sync")
        phone.onHead("item=Work/pear", "sync")
        phone.on015("edit=pear/plum", "cat=Home", "sync")
        phone.onHead("item=Home/chair", "del=apple", "sync")

        val last = phone.on015("dump")
        assertEquals("opened queued=0 pointer=7", last.first())
        assertEquals(setOf("cat Work", "item Work/plum", "cat Home", "item Home/chair"), last.contents())
        assertEquals(7, account.logSize)
    }
}
