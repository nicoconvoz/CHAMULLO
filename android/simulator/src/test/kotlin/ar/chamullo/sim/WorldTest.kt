package ar.chamullo.sim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The digital twin runs the real core on simulated Wi-Fi Direct islands.
class WorldTest {
    @Test
    fun `phones close together end up on one island`() {
        val w = World(seed = 1)
        repeat(5) { w.addPhone("p$it", 10.0 * it, 0.0, village = "A") }
        w.run(60_000)
        val islands = w.islands()
        assertEquals(1, islands.size, "islands: $islands")
        assertEquals(5, islands.values.single().size)
    }

    @Test
    fun `a letter inside an island arrives and its carrier earns a candy`() {
        val w = World(seed = 2)
        val ids = (0 until 4).map { w.addPhone("p$it", 10.0 * it, 0.0, village = "A") }
        w.befriendAll()
        w.run(60_000)
        val (from, to) = w.membersOfSomeIsland().let { it[1] to it[2] } // two members: the host in the middle carries
        w.send(from, to, "hola")
        w.run(30_000)
        assertEquals(1.0, w.report().deliveryRatio)
        assertTrue(w.report().candies.values.sum() >= 1, "the host relayed it: ${w.report().candies}")
    }

    @Test
    fun `two islands out of reach of each other are joined by the ferry`() {
        val w = World(seed = 3)
        // Two villages whose edges are 70 m apart; Wi-Fi reaches 80 m, so the edges see each other.
        repeat(4) { w.addPhone("a$it", 10.0 * it, 0.0, village = "A") }
        repeat(4) { w.addPhone("b$it", 100.0 + 10.0 * it, 0.0, village = "B") }
        w.befriendAll()
        w.run(90_000)
        assertTrue(w.islands().size >= 2, "two villages, maybe a bridge island between them: ${w.islands()}")
        w.send(w.phonesOf("A").first(), w.phonesOf("B").last(), "cruzá el río")
        w.run(10 * 60_000)
        assertEquals(1.0, w.report().deliveryRatio, "delivered across islands")
        assertTrue(w.report().ferryTrips > 0)
    }

    @Test
    fun `a city of 1000 phones forms islands and keeps a history for the charts, in reasonable time`() {
        val w = Scenarios.build(Scenarios.all.single { it.key == "ciudad" }, seed = 1)
        val t0 = System.currentTimeMillis()
        w.run(3 * 60_000)
        val took = System.currentTimeMillis() - t0
        val islands = w.islands()
        assertTrue(islands.size >= 1000 / (ar.chamullo.core.Islands.MAX_MEMBERS + 1), "islands: ${islands.size}")
        assertTrue(w.history().size >= 15, "one sample every 10 s")
        assertTrue(took < 60_000, "3 simulated minutes took ${took} ms")
    }
}
