package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ZoneTest {
    private val obelisco = -34.6037 to -58.3816
    private val cordoba = -31.4201 to -64.1888

    @Test
    fun `cells nest inside their barrio, pueblo and region`() {
        val (lat, lon) = obelisco
        val cell = Zone.of(lat, lon, Zone.CELL)
        for (level in listOf(Zone.MANZANA, Zone.BARRIO, Zone.PUEBLO, Zone.REGION)) {
            assertEquals(Zone.of(lat, lon, level), cell.up(level))
            assertTrue(Zone.of(lat, lon, level).contains(cell))
        }
    }

    @Test
    fun `a zone survives encoding, also with negative indices`() {
        val z = Zone.of(obelisco.first, obelisco.second, Zone.BARRIO)
        assertEquals(z, Zone.decode(z.encode()))
        assertTrue(z.latIndex < 0 && z.lonIndex < 0)
    }

    @Test
    fun `distance is haversine between centers`() {
        val a = Zone.of(obelisco.first, obelisco.second, Zone.CELL)
        val b = Zone.of(cordoba.first, cordoba.second, Zone.CELL)
        assertEquals(646_000.0, a.distanceTo(b), 3_000.0)
    }

    @Test
    fun `bearing points north, east, south and west like a compass`() {
        val (lat, lon) = obelisco
        val here = Zone.of(lat, lon, Zone.CELL)
        assertEquals(0.0, here.bearingTo(Zone.of(lat + 0.05, lon, Zone.CELL)), 1.0)
        assertEquals(90.0, here.bearingTo(Zone.of(lat, lon + 0.05, Zone.CELL)), 1.0)
        assertEquals(180.0, here.bearingTo(Zone.of(lat - 0.05, lon, Zone.CELL)), 1.0)
        assertEquals(270.0, here.bearingTo(Zone.of(lat, lon - 0.05, Zone.CELL)), 1.0)
    }

    @Test
    fun `a cell is about fifty meters and a barrio about two kilometers`() {
        val (lat, lon) = obelisco
        val cell = Zone.of(lat, lon, Zone.CELL)
        assertEquals(55.6, cell.distanceTo(Zone.of(lat + Zone.size(Zone.CELL), lon, Zone.CELL)), 1.0) // finer than a Wi-Fi reach
        val manzana = Zone.of(lat, lon, Zone.MANZANA)
        assertEquals(445.0, manzana.distanceTo(Zone.of(lat + Zone.size(Zone.MANZANA), lon, Zone.MANZANA)), 5.0)
        val barrio = Zone.of(lat, lon, Zone.BARRIO)
        assertEquals(2_224.0, barrio.distanceTo(Zone.of(lat + Zone.size(Zone.BARRIO), lon, Zone.BARRIO)), 10.0)
        assertFalse(barrio.contains(Zone.of(lat + Zone.size(Zone.BARRIO), lon, Zone.CELL)))
    }
}
