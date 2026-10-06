// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Llamadas y videollamadas dentro de la isla (Camino y Carretera §7). */
class CallTest {
    private val ana = Identity.generate("Ana")
    private val beto = Identity.generate("Beto")
    private val host = Identity.generate("Anfitrión")

    /* ---------- the wire: LINK 19, sealed for the contact ---------- */

    @Test
    fun `a call piece is sealed for the contact - the island's host cannot listen`() {
        val voice = ByteArray(640) { it.toByte() }
        val m = CallMsg.seal(ana, beto.nodeId, beto.boxPublic, CallMsg.AUDIO, 42, voice)
        val back = Packet.parse(m.encode()) as CallMsg
        assertEquals(CallMsg.AUDIO, back.op); assertEquals(42, back.call)
        assertArrayEquals(voice, back.open(beto, ana.boxPublic))
        assertNull(back.open(host, ana.boxPublic))
    }

    @Test
    fun `nobody turns a hang-up into an answer on the way`() {
        val m = CallMsg.seal(ana, beto.nodeId, beto.boxPublic, CallMsg.HANGUP, 7, ByteArray(0))
        val changed = CallMsg(m.from, m.to, CallMsg.ANSWER, m.call, m.nonce, m.payload)
        assertNull(changed.open(beto, ana.boxPublic))
    }

    @Test
    fun `only a contact can ring - its card's box key is what opens the call`() {
        val stranger = Identity.generate("Desconocido")
        val m = CallMsg.seal(stranger, beto.nodeId, beto.boxPublic, CallMsg.RING, 1, byteArrayOf(1))
        assertNull(m.open(beto, ana.boxPublic), "pretending to be Ana does not open with Ana's key")
    }

    /* ---------- the call, step by step ---------- */

    @Test
    fun `a call rings, is answered, carries voice and ends with a hang-up`() {
        val c = CallSession(1, beto.nodeId, outgoing = true, video = false, now = 0)
        assertEquals(CallSession.State.CALLING, c.state)
        assertTrue(c.wantsRing(0), "the ring goes out")
        c.onRinging(500)
        assertEquals(CallSession.State.RINGING, c.state)
        c.onAnswer(3_000)
        assertEquals(CallSession.State.ACTIVE, c.state)
        assertEquals(3_000, c.startedAt)
        c.onMedia(10_000); c.tick(20_000)
        assertEquals(CallSession.State.ACTIVE, c.state, "voice keeps it alive")
        c.hangUp(30_000)
        assertEquals(CallSession.State.ENDED, c.state)
        assertEquals(CallSession.End.HUNG_UP, c.end)
        assertEquals(27_000, c.duration())
    }

    @Test
    fun `nobody in the island - the call gives up quickly`() {
        val c = CallSession(1, beto.nodeId, outgoing = true, video = false, now = 0)
        c.tick(CallSession.REACH_MS - 1)
        assertEquals(CallSession.State.CALLING, c.state)
        assertTrue(c.wantsRing(CallSession.RING_EVERY_MS), "the ring repeats until it is heard")
        c.tick(CallSession.REACH_MS)
        assertEquals(CallSession.End.UNREACHABLE, c.end)
    }

    @Test
    fun `it rings but nobody answers`() {
        val c = CallSession(1, beto.nodeId, outgoing = true, video = true, now = 0)
        c.onRinging(100)
        c.tick(CallSession.RING_MS + 100)
        assertEquals(CallSession.End.NO_ANSWER, c.end)
    }

    @Test
    fun `an incoming call can be rejected, or missed`() {
        val rejected = CallSession(2, ana.nodeId, outgoing = false, video = false, now = 0)
        assertEquals(CallSession.State.RINGING, rejected.state)
        rejected.reject(1_000)
        assertEquals(CallSession.End.REJECTED, rejected.end)
        val missed = CallSession(3, ana.nodeId, outgoing = false, video = false, now = 0)
        missed.tick(CallSession.RING_MS)
        assertEquals(CallSession.End.MISSED, missed.end)
    }

    @Test
    fun `a call without voice nor signs of life for a while is cut`() {
        val c = CallSession(1, beto.nodeId, outgoing = false, video = false, now = 0)
        c.answer(1_000)
        c.tick(1_000 + CallSession.SILENT_MS - 1)
        assertEquals(CallSession.State.ACTIVE, c.state)
        c.tick(1_000 + CallSession.SILENT_MS)
        assertEquals(CallSession.End.LOST, c.end)
    }

    @Test
    fun `the other side's busy, rejection or hang-up end my call`() {
        for ((op, end) in listOf(CallMsg.BUSY to CallSession.End.BUSY, CallMsg.REJECT to CallSession.End.REJECTED, CallMsg.HANGUP to CallSession.End.HUNG_UP)) {
            val c = CallSession(1, beto.nodeId, outgoing = true, video = false, now = 0)
            c.onRemote(op, 10)
            assertEquals(end, c.end)
        }
    }

    /* ---------- the media lane: voice and picture over UDP, the pipe as a spare (field test 0.9.1) ---------- */

    @Test
    fun `until the other side confirms it hears UDP, media also goes through the pipe`() {
        val r = MediaRoute()
        assertTrue(!r.useUdp() && r.alsoPipe(), "without its address, only the pipe")
        r.peer = byteArrayOf(192.toByte(), 168.toByte(), 49, 1)
        assertTrue(r.useUdp() && r.alsoPipe(), "both, while it says nothing")
        r.onPing(byteArrayOf(1))
        assertTrue(r.useUdp() && !r.alsoPipe(), "it hears UDP: the pipe is free")
        r.onPing(byteArrayOf(0))
        assertTrue(r.alsoPipe(), "it stopped hearing UDP: the pipe again")
        r.onPing(ByteArray(0))
        assertTrue(r.alsoPipe(), "an old phone's ping says nothing about UDP")
    }

    @Test
    fun `my ping says whether UDP media reached me lately`() {
        val r = MediaRoute()
        assertEquals(0, r.pingPayload(now = 10_000)[0].toInt())
        r.onUdpIn(now = 10_000)
        assertEquals(1, r.pingPayload(now = 10_000 + MediaRoute.UDP_FRESH_MS)[0].toInt())
        assertEquals(0, r.pingPayload(now = 10_000 + MediaRoute.UDP_FRESH_MS + 1)[0].toInt())
    }

    @Test
    fun `a piece that came both ways plays once`() {
        val r = MediaRoute()
        val nonce = ByteArray(24) { 3 }
        assertTrue(r.firstTime(nonce))
        assertTrue(!r.firstTime(nonce.copyOf()))
        assertTrue(r.firstTime(ByteArray(24) { 4 }))
    }

    @Test
    fun `the ring and the answer carry my address in the island, and an old ring still works`() {
        val ip = byteArrayOf(192.toByte(), 168.toByte(), 49, 23)
        val ring = MediaRoute.ring(video = true, ip)
        assertTrue(MediaRoute.isVideo(ring))
        assertArrayEquals(ip, MediaRoute.address(ring, offset = 1))
        assertArrayEquals(ip, MediaRoute.address(MediaRoute.answer(ip), offset = 0))
        val old = byteArrayOf(0)
        assertTrue(!MediaRoute.isVideo(old))
        assertNull(MediaRoute.address(old, offset = 1))
    }
}
