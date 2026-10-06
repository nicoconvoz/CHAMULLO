// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Attachments as sealed letters (Packet Format §5.6): photos, videos, files, a location, a contact. */
class MediaTest {
    private val ana = Identity.generate("Ana")
    private val dani = Identity.generate("Dani")

    @Test
    fun `a photo travels sealed and arrives whole, with its name and type`() {
        val bytes = Crypto.randomBytes(200_000)
        val env = Envelope.letter(ana, dani.card(), Letter.Media(Media.PHOTO, "mate.jpg", "image/jpeg", bytes), 1_000)
        val body = env.open(dani)!!.body as Letter.Media
        assertEquals(Media.PHOTO, body.kind)
        assertEquals("mate.jpg", body.name)
        assertEquals("image/jpeg", body.mime)
        assertArrayEquals(bytes, body.data)
        assertNull(env.open(Identity.generate("Caro")), "only Dani opens it")
    }

    @Test
    fun `a location and a contact card are attachments too`() {
        val here = Letter.Media.location(-34.6037, -58.3816)
        val back = Envelope.letter(ana, dani.card(), here, 1_000).open(dani)!!.body as Letter.Media
        assertEquals(-34.6037 to -58.3816, back.latLon())
        val caro = Identity.generate("Caro").card()
        val shared = Envelope.letter(ana, dani.card(), Letter.Media.contact(caro), 1_000).open(dani)!!.body as Letter.Media
        assertArrayEquals(caro.nodeId, shared.card()!!.nodeId)
        assertTrue(shared.card()!!.verify())
    }

    @Test
    fun `a node sends an attachment, the other keeps it, and too big is refused`() {
        var now = 0L
        val a = Node(ana, MemoryStore(), maxFrame = 60_000) { now }
        val d = Node(dani, MemoryStore(), maxFrame = 60_000) { now }
        val pic = Crypto.randomBytes(150_000)
        val id = a.sendMedia(d.card(), Letter.Media(Media.PHOTO, "foto.jpg", "image/jpeg", pic))
        assertNotNull(id)
        var got: NodeEvent.LetterReceived? = null
        repeat(20) { now += 100; a.tick(); for (f in a.drainOutbox()) d.onFrame(f).filterIsInstance<NodeEvent.LetterReceived>().firstOrNull()?.let { got = it }; d.tick(); for (f in d.drainOutbox()) a.onFrame(f) }
        assertEquals("📷 Foto", got!!.text)
        val kept = d.store.messages(ana.nodeId).single()
        assertEquals(Media.PHOTO, kept.media!!.kind)
        assertArrayEquals(pic, d.store.loadMedia(kept.msgId))
        assertNull(a.sendMedia(d.card(), Letter.Media(Media.FILE, "grande.zip", "application/zip", ByteArray(Media.MAX_BYTES + 1))), "over 3 MB")
    }

    @Test
    fun `a contact can be deleted`() {
        val s = MemoryStore()
        s.saveContact(dani.card())
        s.deleteContact(dani.nodeId)
        assertNull(s.contact(dani.nodeId))
    }

    @Test
    fun `ICEBREAK's icons are read into strokes - paths, circles and rounded boxes`() {
        val shapes = Svg.parse("""<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7"/><rect x="2.5" y="6" width="13" height="12" rx="2.5"/><path d="m6 9 6 6 6-6M4 4h2v3H4z"/>""")
        assertEquals(4, shapes.size)
        assertTrue(shapes[0].ops.isNotEmpty() && shapes[0].ops.first() is Svg.Op.Move)
        val tick = shapes[3].ops
        assertEquals(Svg.Op.Move(6f, 9f), tick[0])
        assertEquals(Svg.Op.Line(12f, 15f), tick[1])
        assertEquals(Svg.Op.Line(18f, 9f), tick[2])
        assertEquals(Svg.Op.Move(4f, 4f), tick[3])
        assertEquals(Svg.Op.Line(6f, 4f), tick[4]) // h2
        assertEquals(Svg.Op.Line(6f, 7f), tick[5]) // v3
        assertTrue(tick.last() is Svg.Op.Close)
        val arc = Svg.parse("""<path d="M12 3a6 6 0 0 0-3.6 10.8"/>""").single().ops
        assertTrue(arc.drop(1).all { it is Svg.Op.Cubic }, "arcs become curves")
        val end = arc.last() as Svg.Op.Cubic
        assertEquals(8.4f, end.x, 0.01f); assertEquals(13.8f, end.y, 0.01f)
    }
}
