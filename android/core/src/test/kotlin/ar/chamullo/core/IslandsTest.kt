package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Islas (Capitán's design): one Wi-Fi Direct group per island, newcomers join instead of founding, and members take
 * turns as the ferry that carries letters to a neighboring island.
 */
class IslandsTest {
    private fun host(id: String, members: List<String> = emptyList()) =
        Cartel(id, "H$id", island = id, ssid = "DIRECT-CH-$id", passphrase = "clave-$id", host = true, roster = members)

    private fun member(id: String, island: String) =
        Cartel(id, "M$id", island = island, ssid = "", passphrase = "", host = false, roster = emptyList())

    private val alone = IslandState(island = null, host = false, members = emptyList())

    @Test
    fun `alone and nobody around - found my own island`() {
        assertEquals(IslandAction.Host, Islands.decide("aaaa", alone, emptyList(), now = 0))
    }

    @Test
    fun `an island with room nearby - join it instead of founding one`() {
        val a = Islands.decide("zzzz", alone, listOf(host("bbbb", listOf("cccc"))), now = 0)
        assertEquals(IslandAction.Join("bbbb", "DIRECT-CH-bbbb", "clave-bbbb"), a)
    }

    @Test
    fun `the biggest island with room wins`() {
        val small = host("bbbb", listOf("c1"))
        val big = host("dddd", listOf("e1", "e2", "e3"))
        assertEquals("dddd", (Islands.decide("zzzz", alone, listOf(small, big), now = 0) as IslandAction.Join).island)
    }

    @Test
    fun `a full island is not joined - found a neighboring one`() {
        val full = host("bbbb", (1..Islands.MAX_MEMBERS).map { "m$it" })
        assertEquals(IslandAction.Host, Islands.decide("zzzz", alone, listOf(full), now = 0))
    }

    @Test
    fun `two lonely hosts merge - the one with the bigger id joins the other`() {
        val meLonely = IslandState(island = "zzzz", host = true, members = emptyList())
        assertEquals(IslandAction.Join("bbbb", "DIRECT-CH-bbbb", "clave-bbbb"), Islands.decide("zzzz", meLonely, listOf(host("bbbb")), now = 0))
        val otherLonely = IslandState(island = "bbbb", host = true, members = emptyList())
        assertEquals(IslandAction.Stay, Islands.decide("bbbb", otherLonely, listOf(host("zzzz")), now = 0))
    }

    @Test
    fun `a host with members never leaves its island`() {
        val me = IslandState(island = "aaaa", host = true, members = listOf("m1"))
        assertEquals(IslandAction.Stay, Islands.decide("aaaa", me, listOf(host("bbbb", listOf("x"))), now = 0))
    }

    @Test
    fun `members take turns as the ferry to a neighboring island`() {
        val roster = listOf("m1", "m2", "m3")
        val other = host("bbbb", listOf("x1"))
        val turns = (0 until 3).map { turn ->
            val now = turn * Islands.FERRY_TURN_MS
            roster.filter { me ->
                Islands.decide(me, IslandState("aaaa", host = false, members = roster), listOf(host("aaaa", roster), other), now) is IslandAction.Ferry
            }
        }
        assertEquals(listOf(listOf("m1"), listOf("m2"), listOf("m3")), turns, "one ferry per turn, rotating")
    }

    @Test
    fun `the ferry goes to the other island with its key`() {
        val roster = listOf("m1")
        val a = Islands.decide("m1", IslandState("aaaa", host = false, members = roster), listOf(host("aaaa", roster), host("bbbb")), now = 0)
        assertEquals(IslandAction.Ferry("bbbb", "DIRECT-CH-bbbb", "clave-bbbb"), a)
    }

    @Test
    fun `no other island in sight - the member stays`() {
        val roster = listOf("m1", "m2")
        assertEquals(IslandAction.Stay, Islands.decide("m1", IslandState("aaaa", false, roster), listOf(host("aaaa", roster), member("m2", "aaaa")), now = 0))
    }

    @Test
    fun `a cartel fits in a Wi-Fi Direct service record and reads back`() {
        val c = Cartel("0123456789abcdef", "Hermano", "0123456789abcdef", "DIRECT-CH-012345", "clave-xyz", host = true, roster = listOf("aaaaaaaa", "bbbbbbbb"))
        val txt = c.toTxt()
        assertEquals(true, txt.entries.sumOf { it.key.length + it.value.length + 2 } <= 255)
        assertEquals(c, Cartel.fromTxt(txt))
    }

    @Test
    fun `bridge - a member that sees people of another island but not its host founds a bridge island at the border`() {
        val roster = listOf("m1", "m2")
        val state = IslandState("aaaa", host = false, members = roster)
        val seen = listOf(host("aaaa", roster), member("x1", island = "bbbb"))
        assertEquals(IslandAction.Host, Islands.decide("m1", state, seen, now = 0))
    }

    @Test
    fun `bridge - a lonely bridge host keeps its post instead of merging back`() {
        val me = IslandState(island = "m1", host = true, members = emptyList())
        val seen = listOf(host("aaaa", listOf("m2")), member("x1", island = "bbbb"))
        assertEquals(IslandAction.Stay, Islands.decide("m1", me, seen, now = 0))
    }

    @Test
    fun `ferry - one trip per turn, not a trip every few seconds`() {
        val roster = listOf("m1")
        val seen = listOf(host("aaaa", roster), host("bbbb"))
        val turn = Islands.turnOf(0)
        assertEquals(IslandAction.Stay, Islands.decide("m1", IslandState("aaaa", false, roster, ferriedTurn = turn), seen, now = 0))
        assertTrue(Islands.decide("m1", IslandState("aaaa", false, roster, ferriedTurn = turn), seen, now = Islands.FERRY_TURN_MS) is IslandAction.Ferry)
    }

    @Test
    fun `ferry seat - an island keeps one seat free for visiting ferries`() {
        val almostFull = host("bbbb", (1 until Islands.MAX_MEMBERS).map { "m$it" })
        assertEquals(IslandAction.Host, Islands.decide("zzzz", alone, listOf(almostFull), now = 0), "a newcomer does not take the ferry seat")
        val roster = listOf("m1")
        assertTrue(Islands.decide("m1", IslandState("aaaa", false, roster), listOf(host("aaaa", roster), almostFull), now = 0) is IslandAction.Ferry,
            "a ferry can still visit")
    }
}
