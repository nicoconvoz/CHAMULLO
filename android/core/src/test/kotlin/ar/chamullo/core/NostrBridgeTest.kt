// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The Internet bridge over Nostr, against a small Nostr relay that speaks the real protocol (NIP-01) on localhost. */
class NostrBridgeTest {
    /** A tiny Nostr relay: keeps events, answers REQ with what matches, and pushes new events to live subscriptions. */
    private class FakeRelay {
        val server = ServerSocket(0)
        val events = CopyOnWriteArrayList<Map<*, *>>()
        private val subs = CopyOnWriteArrayList<Triple<OutputStream, String, Map<*, *>>>()
        val url get() = "ws://localhost:${server.localPort}"

        init { Thread { while (!server.isClosed) runCatching { val s = server.accept(); Thread { serve(s) }.apply { isDaemon = true }.start() } }.apply { isDaemon = true }.start() }

        private fun serve(s: Socket) = runCatching {
            val input = DataInputStream(s.getInputStream()); val out = s.getOutputStream()
            var key = ""
            while (true) { val line = readLine(input); if (line.isEmpty()) break; if (line.startsWith("Sec-WebSocket-Key:", true)) key = line.substringAfter(':').trim() }
            val accept = Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray()); out.flush()
            while (true) {
                val b0 = input.readUnsignedByte(); val b1 = input.readUnsignedByte()
                var len = (b1 and 0x7f).toLong()
                if (len == 126L) len = input.readUnsignedShort().toLong() else if (len == 127L) len = input.readLong()
                val mask = ByteArray(4).also { input.readFully(it) }
                val data = ByteArray(len.toInt()).also { input.readFully(it) }
                for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
                if (b0 and 0x0f == 8) break
                handle(out, Json.parse(String(data)) as List<*>)
            }
        }.also { s.close() }

        private fun readLine(i: DataInputStream) = buildString { while (true) { val c = i.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) append(c.toChar()) } }

        private fun send(out: OutputStream, text: String) = synchronized(out) {
            val d = text.toByteArray()
            out.write(0x81)
            if (d.size < 126) out.write(d.size) else if (d.size < 65_536) { out.write(126); out.write(d.size shr 8); out.write(d.size and 0xff) }
            else { out.write(127); for (k in 7 downTo 0) out.write(((d.size.toLong() shr (8 * k)) and 0xff).toInt()) }
            out.write(d); out.flush()
        }

        private fun matches(f: Map<*, *>, e: Map<*, *>): Boolean {
            (f["kinds"] as? List<*>)?.let { ks -> if (ks.none { (it as Number).toInt() == (e["kind"] as Number).toInt() }) return false }
            (f["since"] as? Number)?.let { if ((e["created_at"] as Number).toLong() < it.toLong()) return false }
            for ((k, v) in f) if (k is String && k.startsWith("#")) {
                val tag = k.drop(1)
                val have = (e["tags"] as List<*>).map { it as List<*> }.filter { it[0] == tag }.map { it[1] }
                if ((v as List<*>).none { it in have }) return false
            }
            return true
        }

        private fun handle(out: OutputStream, m: List<*>) {
            when (m[0]) {
                "EVENT" -> {
                    val e = m[1] as Map<*, *>
                    events += e
                    send(out, "[\"OK\",${Json.str(e["id"] as String)},true,\"\"]")
                    for ((o, id, f) in subs) if (matches(f, e)) runCatching { send(o, "[\"EVENT\",${Json.str(id)},${Nostr.Event.fromJson(e).toJson()}]") }
                }
                "REQ" -> {
                    val id = m[1] as String; val f = m[2] as Map<*, *>
                    subs.removeIf { it.first === out && it.second == id }
                    subs += Triple(out, id, f)
                    for (e in events) if (matches(f, e)) send(out, "[\"EVENT\",${Json.str(id)},${Nostr.Event.fromJson(e).toJson()}]")
                    send(out, "[\"EOSE\",${Json.str(id)}]")
                }
            }
        }
    }

    private val relay = FakeRelay()
    private val bridges = mutableListOf<NostrBridge>()
    private val lat = -34.59

    @AfterEach fun stop() { bridges.forEach { it.stop() }; relay.server.close() }

    private fun bridge(name: String, at: Zone, inbox: LinkedBlockingQueue<ByteArray>) =
        NostrBridge(listOf(relay.url), Identity.generate(name)) { inbox.put(it) }.also { it.place(at); it.start(); bridges += it }

    private fun waitUntil(what: () -> Boolean) { repeat(200) { if (what()) return; Thread.sleep(50) } }

    @Test
    fun `bridges find each other on Nostr by zone and hand frames over, with no server of ours`() {
        val got = LinkedBlockingQueue<ByteArray>()
        val ana = bridge("Ana", Zone.of(lat, -58.4300), LinkedBlockingQueue())
        val sur = bridge("Sur", Zone.of(lat, -58.3650), got)
        val zone = Zone.of(lat, -58.3650, Zone.BARRIO)
        waitUntil { ana.peersIn(zone).isNotEmpty() }
        assertArrayEquals(sur.identity.nodeId, ana.peersIn(zone).single())
        ana.send(sur.identity.nodeId, byteArrayOf(7, 7, 7))
        assertArrayEquals(byteArrayOf(7, 7, 7), got.poll(10, TimeUnit.SECONDS))
        assertTrue(relay.events.all { Nostr.Event.fromJson(it).verify() }, "every event is signed")
    }

    @Test
    fun `nobody can register in someone else's name - the CHAMULLO key signs the Nostr key`() {
        val ana = bridge("Ana", Zone.of(lat, -58.4300), LinkedBlockingQueue())
        val zone = Zone.of(lat, -58.3650, Zone.BARRIO)
        val victim = Identity.generate("Víctima")
        val liar = Identity.generate("Chanta")
        val fake = Nostr.event(liar, NostrBridge.KIND_DIRECTORY, listOf(listOf("d", "chamullo-bridge"), listOf("t", "chamullo"),
            listOf("g", zone.encode().toHex()), listOf("n", victim.nodeId.toHex())), liar.sign("NOSTR", Nostr.publicKey(liar)).toHex())
        relay.events += Json.parse(fake.toJson()) as Map<*, *>
        ana.peersIn(zone); Thread.sleep(1_000)
        assertEquals(0, ana.peersIn(zone).size, "the liar signed with its own key, not the victim's")
    }
}
