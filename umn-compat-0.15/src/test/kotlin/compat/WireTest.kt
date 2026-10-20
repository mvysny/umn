package compat

import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A: a HEAD client against the 0.12 server sees what a 0.15 client sees. Each case runs the same probe on both
 * versions, on accounts of their own, and compares.
 */
@Testcontainers(disabledWithoutDocker = true)
class WireTest {
    private fun probe(account: Account, rewind: Int, push: Int, expect: Int) =
        arrayOf("wire", ProdServer.wsUrl, account.user, account.pass, "$rewind", "$push", "$expect")

    @Test
    fun `A1 connect, push and get the ACKs, pull`() {
        val head = Account()
        val headSaw = Head.run(*probe(head, 0, 3, 3))
        val old = Account()
        assertEquals(Umn015.run(*probe(old, 0, 3, 3)), headSaw)
        assertEquals(3, headSaw.number("acks"))
        assertEquals(3, head.logSize)
    }

    @Test
    fun `A2 a wrong password or an unknown user is refused alike`() {
        val account = Account()
        val wrongPassword = arrayOf("wire-login", ProdServer.wsUrl, account.user, "wrong")
        val headSaw = Head.run(*wrongPassword)
        assertEquals(Umn015.run(*wrongPassword), headSaw)
        assertTrue(headSaw.single().startsWith("threw com.gitlab.mvysny.umn.sync.InvalidUsernamePasswordException: "), "$headSaw")
        val unknownUser = arrayOf("wire-login", ProdServer.wsUrl, "nobody", "wrong")
        assertEquals(Umn015.run(*unknownUser), Head.run(*unknownUser))
        assertEquals(listOf("started"), Head.run("wire-login", ProdServer.wsUrl, account.user, account.pass))
    }

    @Test
    fun `A3 reconnecting with rewind pulls only the records from there on`() {
        val account = Account()
        val all = Head.run(*probe(account, 0, 5, 5)).filter { it.startsWith("record ") }
        val head = Head.run(*probe(account, 2, 0, 3))
        assertEquals(all.drop(2), head.filter { it.startsWith("record ") })
        assertEquals(Umn015.run(*probe(account, 2, 0, 3)), head)
    }
}
