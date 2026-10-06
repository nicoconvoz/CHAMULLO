package ar.chamullo.sim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Economy & Governance (spec 04) on the carries the twin observed.
class EconomyTest {
    private fun carry(carrier: String, origin: String, dest: String, alternatives: Int = 3, village: String = "A") =
        World.Carry(carrier, origin, dest, alternatives, village)

    @Test
    fun `where paths are scarce a carry pays more, within the floor and the ceiling`() {
        assertEquals(Economy.CEILING, Economy.scarcity(0))
        assertEquals(Economy.FLOOR, Economy.scarcity(1000))
        assertTrue(Economy.scarcity(1) > Economy.scarcity(10))
    }

    @Test
    fun `a thousand trips with the same disguises count less than serving many different people`() {
        val same = List(100) { carry("tramposo", "x", "y") }
        val many = (0 until 10).map { carry("honesto", "o$it", "d$it") }
        val s = Economy.scores(same + many)
        assertTrue(s.getValue("honesto") > s.getValue("tramposo"), "$s")
    }

    @Test
    fun `the court is computed, not appointed - king and nobles by score, per village and national`() {
        val carries = listOf(carry("ana", "a", "b", village = "A"), carry("ana", "c", "d", village = "A"), carry("beto", "a", "b", village = "A"),
            carry("caro", "e", "f", alternatives = 0, village = "B"))
        val court = Economy.courts(carries, nobles = 1)
        assertEquals("ana", court.getValue("A").king)
        assertEquals(listOf("beto"), court.getValue("A").nobles)
        assertEquals("caro", court.getValue("B").king)
        assertEquals("caro", Economy.national(carries, nobles = 1).king, "the scarce carry in B weighs more than two easy ones in A")
    }

    @Test
    fun `a fixed daily bag is shared by score - nobody prints extra candies`() {
        val bag = Economy.dailyShare(mapOf("ana" to 3.0, "beto" to 1.0), bag = 1000)
        assertEquals(750, bag.getValue("ana")); assertEquals(250, bag.getValue("beto"))
    }
}
