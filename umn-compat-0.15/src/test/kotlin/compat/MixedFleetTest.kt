package compat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * C: one account, its nodes on both versions for good: the phone on 0.16, Aedict-Online (`web`) and a never-updated
 * tablet on 0.15. Every case ends with the nodes holding the same documents.
 */
@Testcontainers(disabledWithoutDocker = true)
class MixedFleetTest {
    @TempDir
    lateinit var dir: Path
    private val account = Account()
    private val phone get() = account.node(dir, "phone.db")
    private val web get() = account.node(dir, "web.db")
    private val tablet get() = account.node(dir, "tablet.db")

    @Test
    fun `C1 0_15 reads every record 0_16 writes, live and after a reconnect`() {
        web.on015("cat=Work", "sync")
        val session = web.start015("sync", "ready", "await=6", "dump")
        session.awaitLine("READY")

        // creates, updates and deletes, while Aedict-Online's session stays connected
        val phoneWrote = phone.onHead("sync", "item=Work/apple", "item=Work/pear", "edit=apple/apple2", "rename=Work/Job", "del=pear", "sync", "dump")
        assertEquals(setOf("cat Job", "item Job/apple2"), phoneWrote.contents())
        assertEquals(phoneWrote.docs(), session.await().docs())

        val phoneWroteMore = phone.onHead("cat=Home", "item=Home/chair", "delcat=Job", "sync", "dump")
        assertEquals(setOf("cat Home", "item Home/chair"), phoneWroteMore.contents())
        assertEquals(phoneWroteMore.docs(), web.on015("sync", "dump").docs())
    }

    @Test
    fun `C2 0_16 pulls what the 0_15 nodes write meanwhile`() {
        phone.onHead("cat=Work", "item=Work/apple", "sync")
        tablet.on015("sync", "item=Work/pear", "edit=apple/apple2", "sync")
        web.on015("sync", "rename=Work/Job", "sync")

        val pulled = phone.onHead("sync", "dump")
        assertEquals(setOf("cat Job", "item Job/apple2", "item Job/pear"), pulled.contents())
        assertEquals(pulled.docs(), tablet.on015("sync", "dump").docs())
    }

    @Test
    fun `C3 concurrent edits of one document on 0_15 and 0_16 converge`() {
        phone.onHead("cat=Work", "item=Work/apple", "sync")
        tablet.on015("sync")
        tablet.on015("edit=apple/apple-tablet")
        phone.onHead("edit=apple/apple-phone")
        tablet.on015("sync")

        // the phone pushes last, so its edit wins on both
        val onPhone = phone.onHead("sync", "dump")
        assertEquals(setOf("cat Work", "item Work/apple-phone"), onPhone.contents())
        assertEquals(onPhone.docs(), tablet.on015("sync", "dump").docs())
    }

    /**
     * A log both versions wrote, initial populations ([com.gitlab.mvysny.umn.core.LogRecord.DocumentCreated2]) by
     * either included; returns the phone's dump at its end.
     */
    private fun mixedHistory(): List<String> {
        tablet.on015("plain", "cat=Tablet", "item=Tablet/t1")
        tablet.on015("sync")
        phone.on015("plain", "cat=Phone", "item=Phone/p1")
        phone.onHead("sync")
        phone.onHead("item=Tablet/p2", "edit=t1/t1-edited", "sync")
        tablet.on015("sync", "del=p1", "rename=Phone/Phone2", "sync")
        web.on015("sync", "cat=Web", "item=Web/w1", "delcat=Phone2", "sync")
        val history = phone.onHead("sync", "dump")
        assertEquals(setOf("cat Tablet", "item Tablet/t1-edited", "item Tablet/p2", "cat Web", "item Web/w1"), history.contents())
        return history
    }

    @Test
    fun `C4 a fresh 0_16 node pulls the whole mixed log`() {
        val history = mixedHistory()
        val fresh = account.node(dir, "new-phone.db").onHead("sync", "dump")
        assertEquals("opened queued=0 pointer=0", fresh.first())
        assertEquals(history.docs(), fresh.docs())
    }

    @Test
    fun `C5 a fresh 0_15 node pulls the whole mixed log`() {
        val history = mixedHistory()
        val fresh = account.node(dir, "new-web.db").on015("sync", "dump")
        assertEquals("opened queued=0 pointer=0", fresh.first())
        assertEquals(history.docs(), fresh.docs())
    }
}
