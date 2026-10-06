package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompassTest {
    private val lat = -34.5900 // the latitude of the eastern barrio's center: east is really east
    private val lon = -58.4000
    private val me = Zone.of(lat, lon)
    private val east = Zone.of(lat, lon + 0.08, Zone.BARRIO) // ~7 km east
    private fun cell(dLat: Double, dLon: Double) = Zone.of(lat + dLat, lon + dLon)
    private fun n(name: String, c: Zone?, stable: Boolean = true) = Compass.Peer(name.toByteArray().copyOf(32), c, stable)

    @Test
    fun `without a destination zone the letter goes by bounded eco, as in v0_1`() {
        assertEquals(Compass.Plan.Eco(null), Compass.plan(me, null, null, 0, listOf(n("a", cell(0.0, 0.001))), null, origin = true))
    }

    @Test
    fun `inside the destination barrio it becomes a local eco with a bounded budget`() {
        val home = me.up(Zone.BARRIO)
        assertEquals(Compass.Plan.Eco(Compass.LOCAL_TTL - 1), Compass.plan(me, home, null, 0, emptyList(), null, origin = false))
        assertEquals(Compass.Plan.Eco(1), Compass.plan(me, home, 2, 0, emptyList(), null, origin = false))
        assertEquals(Compass.Plan.Stop, Compass.plan(me, home, 0, 0, emptyList(), null, origin = false))
    }

    @Test
    fun `the river picks the neighbors that get it closest, k streams from the origin and one per carrier`() {
        val far = n("far", cell(0.0, 0.002)); val near = n("near", cell(0.0, 0.001)); val back = n("back", cell(0.0, -0.001))
        val liar = n("liar", cell(0.0, 0.05)) // 4.5 km away over Wi-Fi: not plausible
        val wobbly = n("wobbly", cell(-0.001, 0.001), stable = false) // some progress, but unstable: half the score
        val all = listOf(back, near, liar, wobbly, far)
        val fromOrigin = Compass.plan(me, east, null, 0, all, null, origin = true) as Compass.Plan.River
        assertEquals(listOf("far", "near"), fromOrigin.takers.map { String(it.id).trimEnd('\u0000') })
        assertEquals(2, fromOrigin.alternatives) // the stable ones with progress: far and near
        val fromCarrier = Compass.plan(me, east, null, 0, all, null, origin = false) as Compass.Plan.River
        assertEquals(listOf("far"), fromCarrier.takers.map { String(it.id).trimEnd('\u0000') })
    }

    @Test
    fun `the lake widens to plus or minus ninety degrees, never back to the giver`() {
        val giver = n("giver", cell(0.001, 0.0)) // north: bearing 0, within 90 of east but it gave me the letter
        val southEast = n("se", cell(-0.001, 0.0))  // south: 180 from me, 90 from the bearing (east)
        val plan = Compass.plan(me, east, null, 0, listOf(giver, southEast), giver.id, origin = false)
        assertEquals(Compass.Plan.Lake(southEast, 1), plan)
        val ahead = n("ahead", cell(0.0, 0.002))
        assertEquals(Compass.Plan.Hold, Compass.plan(me, east, null, 0, listOf(ahead), ahead.id, origin = false), "not even by the river")
    }

    @Test
    fun `when nothing is within ninety degrees, the right hand rule goes around`() {
        val west = n("west", cell(0.0, -0.001))
        val sw = n("sw", cell(-0.001, -0.001))
        val nw = n("nw", cell(0.001, -0.001))
        // bearing east = 90: clockwise from it, south-west (225) comes before west (270) and north-west (315)
        assertEquals(Compass.Plan.Lake(sw, 2), Compass.plan(me, east, null, 1, listOf(west, nw, sw), null, origin = false))
    }

    @Test
    fun `with the lake budget spent, or with nobody around, the carrier keeps it`() {
        val west = n("west", cell(0.0, -0.001))
        assertEquals(Compass.Plan.Hold, Compass.plan(me, east, null, Compass.LAKE_BUDGET, listOf(west), null, origin = false))
        assertEquals(Compass.Plan.Hold, Compass.plan(me, east, null, 0, emptyList(), null, origin = false))
        assertEquals(Compass.Plan.Hold, Compass.plan(me, east, null, 0, listOf(n("same", me)), null, origin = false))
    }

    @Test
    fun `progress resets the detour, and refused neighbors are left out`() {
        val near = n("near", cell(0.0, 0.001))
        val plan = Compass.plan(me, east, null, 2, listOf(near), null, origin = false)
        assertTrue(plan is Compass.Plan.River)
        assertEquals(Compass.Plan.Hold, Compass.plan(me, east, null, 0, listOf(near), null, origin = false, refused = mapOf(near.id.toHex() to 0L)))
    }

    @Test
    fun `a refusal only counts for a while - islands come and go`() {
        val near = n("near", cell(0.0, 0.001))
        val refused = mapOf(near.id.toHex() to 0L)
        assertEquals(Compass.Plan.Hold, Compass.plan(me, east, null, 0, listOf(near), null, origin = false, refused = refused, now = Compass.REFUSED_MS - 1))
        assertTrue(Compass.plan(me, east, null, 0, listOf(near), null, origin = false, refused = refused, now = Compass.REFUSED_MS) is Compass.Plan.River)
    }

    @Test
    fun `the local eco reaches as deep as the old eco did, but only inside the barrio`() {
        assertEquals(8, Compass.LOCAL_TTL)
    }

    @Test
    fun `the hop budget grows with the distance`() {
        assertEquals(8, Compass.maxHops(me, null))
        assertEquals(Compass.LOCAL_TTL + 4, Compass.maxHops(me, me.up(Zone.BARRIO))) // already there: only the local search
        val d = me.distanceTo(east)
        assertEquals(Math.ceil(d / Compass.HOP_M).toInt() + Compass.LOCAL_TTL + 4, Compass.maxHops(me, east))
        assertEquals(255, Compass.maxHops(me, Zone.of(-31.42, -64.19, Zone.BARRIO)))
    }
}
