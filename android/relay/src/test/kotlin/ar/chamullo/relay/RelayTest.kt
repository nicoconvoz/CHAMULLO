package ar.chamullo.relay

import ar.chamullo.core.Identity
import ar.chamullo.core.RelayBridge
import ar.chamullo.core.Zone
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RelayTest {
    private val server = RelayServer().also { it.start(0) }
    private val url = "http://localhost:${server.port}"
    private val lat = -34.59
    private val north = Zone.of(lat, -58.4300)
    private val south = Zone.of(lat, -58.3650)
    private val clients = mutableListOf<RelayBridge>()

    @AfterEach fun stop() { clients.forEach { it.stop() }; server.stop() }

    private fun client(name: String, at: Zone, inbox: LinkedBlockingQueue<ByteArray>) =
        RelayBridge(url, Identity.generate(name)) { inbox.put(it) }.also { it.place(at); it.start(); clients += it }

    private fun waitUntil(what: () -> Boolean) { repeat(100) { if (what()) return; Thread.sleep(50) } }

    @Test
    fun `a bridge finds the bridges of a zone and hands them a frame over Internet`() {
        val got = LinkedBlockingQueue<ByteArray>()
        val ana = client("Ana", north, LinkedBlockingQueue())
        val sur = client("Sur", south, got)
        waitUntil { server.bridges() == 2 }
        val zone = south.up(Zone.BARRIO)
        waitUntil { ana.peersIn(zone).isNotEmpty() } // the first ask fills the cache
        assertArrayEquals(sur.identity.nodeId, ana.peersIn(zone).single())
        ana.send(sur.identity.nodeId, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), got.poll(10, TimeUnit.SECONDS))
        assertEquals(3L, server.bytesFrom(ana.identity.nodeId))
    }

    @Test
    fun `a manzana without bridges falls back to the bridges of its barrio`() {
        val sur = client("Sur", south, LinkedBlockingQueue())
        val ana = client("Ana", north, LinkedBlockingQueue())
        waitUntil { server.bridges() == 2 }
        val emptyBlockSameBarrio = Zone.of(lat, -58.3700, Zone.MANZANA)
        assertTrue(!emptyBlockSameBarrio.contains(south) && emptyBlockSameBarrio.up(Zone.BARRIO).contains(south))
        waitUntil { ana.peersIn(emptyBlockSameBarrio).isNotEmpty() }
        assertArrayEquals(sur.identity.nodeId, ana.peersIn(emptyBlockSameBarrio).single())
    }

    @Test
    fun `nobody reads another's mailbox or speaks in another's name`() {
        val c = URI("$url/inbox").toURL().openConnection() as HttpURLConnection
        c.setRequestProperty("X-Chamullo-Id", Identity.generate("Ana").nodeId.joinToString("") { "%02x".format(it) })
        c.setRequestProperty("X-Chamullo-Ts", System.currentTimeMillis().toString())
        c.setRequestProperty("X-Chamullo-Sig", "00".repeat(64))
        assertEquals(403, c.responseCode)
    }
}
