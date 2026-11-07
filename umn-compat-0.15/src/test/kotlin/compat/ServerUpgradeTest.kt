package compat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G: Aedict-Online's server upgraded in place — HEAD's proxy server opens the `umn_proxy_log` file the 0.12 image
 * wrote, and its nodes, on either version, sync on as if nothing happened.
 */
@Testcontainers(disabledWithoutDocker = true)
class ServerUpgradeTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `G1 HEAD's server takes over the 0_12 server's database`() {
        val old = ProdServer()
        val kept = Account(old)
        val repassworded = Account(old)
        val deleted = Account(old)
        val before = kept.node(dir, "phone.db").on015("cat=Work", "item=Work/apple", "item=Work/pear", "edit=apple/apple2", "del=pear", "sync", "dump")
        repassworded.node(dir, "repassworded.db").on015("cat=Home", "sync")
        Server.createUser(old.adminUrl, repassworded.user, "new")
        deleted.node(dir, "deleted.db").on015("cat=Gone", "sync")
        Server.deleteUser(old.adminUrl, deleted.user)
        old.stopInto(dir.resolve("db"))

        HeadServer(dir.resolve("db")).use { head ->
            val users = Server.users(head.adminUrl)
            assertTrue(kept.user in users && repassworded.user in users, users)
            assertFalse(deleted.user in users, users)
            val account = kept.movedTo(head)
            assertEquals(5, account.logSize)
            assertEquals(1, repassworded.movedTo(head).logSize)

            // the passwords hashed by 0.12 still log in, a deleted user stays out
            assertEquals(listOf("started"), Head.run("wire-login", head.wsUrl, repassworded.user, "new"))
            assertTrue(Head.run("wire-login", head.wsUrl, repassworded.user, repassworded.pass).single().startsWith("threw "))
            assertTrue(Head.run("wire-login", head.wsUrl, deleted.user, deleted.pass).single().startsWith("threw "))

            // the phone pulls nothing new and appends after the 0.12 server's records
            val resumed = account.node(dir, "phone.db").on015("sync", "dump")
            assertEquals("opened queued=0 pointer=5", resumed.first())
            assertEquals(5, resumed.number("log"))
            assertEquals(before.docs(), resumed.docs())
            val phone = account.node(dir, "phone.db").on015("item=Work/plum", "sync", "dump")
            assertEquals(6, phone.number("log"))
            assertEquals(setOf("cat Work", "item Work/apple2", "item Work/plum"), phone.contents())

            // fresh nodes on both versions read the whole log, the 0.12 server's records included
            assertEquals(phone.docs(), account.node(dir, "new-phone.db").onHead("sync", "dump").docs())
            assertEquals(phone.docs(), account.node(dir, "new-web.db").on015("sync", "dump").docs())
        }
    }

    @Test
    fun `G2 a 0_12 server takes back the log HEAD's server compacted on startup`() {
        val old = ProdServer()
        val kept = Account(old)
        val deleted = Account(old)
        kept.node(dir, "phone.db").on015("cat=Work", "item=Work/apple", "item=Work/pear", "sync")
        deleted.node(dir, "deleted.db").on015("cat=Gone", "sync")
        Server.deleteUser(old.adminUrl, deleted.user)
        old.stopInto(dir.resolve("db"))

        // HEAD takes over and the phone syncs a change through it; then a restart compacts the log, for a rollback
        HeadServer(dir.resolve("db")).use { head ->
            kept.movedTo(head).node(dir, "phone.db").on015("item=Work/plum", "sync")
        }
        val log = dir.resolve("db/umn_proxy_log")
        val sizeBefore = Files.size(log)
        HeadServer(dir.resolve("db")).close()
        assertTrue(Files.size(log) < sizeBefore, "compacted: ${Files.size(log)} vs $sizeBefore")

        ProdServer(log).use { back ->
            val users = Server.users(back.adminUrl)
            assertTrue(kept.user in users, users)
            assertFalse(deleted.user in users, users)
            val account = kept.movedTo(back)
            assertEquals(4, account.logSize)

            // the phone pulls nothing new and appends after HEAD's records
            val resumed = account.node(dir, "phone.db").on015("sync", "dump")
            assertEquals("opened queued=0 pointer=4", resumed.first())
            val phone = account.node(dir, "phone.db").on015("item=Work/grape", "sync", "dump")
            assertEquals(5, phone.number("log"))
            assertEquals(setOf("cat Work", "item Work/apple", "item Work/pear", "item Work/plum", "item Work/grape"), phone.contents())

            // a fresh node reads the whole log, through the 0.12 server
            assertEquals(phone.docs(), account.node(dir, "new-web.db").on015("sync", "dump").docs())
        }
    }
}
