// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocketFactory

/** A small WebSocket client (RFC 6455), enough for Nostr relays: text frames, ping/pong, close. Blocking; one reader thread. */
class WebSocket(private val url: String, private val onText: (String) -> Unit, private val onClose: () -> Unit) {
    private var socket: Socket? = null
    private var out: OutputStream? = null
    @Volatile var open = false; private set

    fun connect() {
        val u = URI(url)
        val tls = u.scheme == "wss"
        val port = if (u.port > 0) u.port else if (tls) 443 else 80
        val s = if (tls) SSLSocketFactory.getDefault().createSocket() else Socket()
        s.connect(InetSocketAddress(u.host, port), 10_000)
        s.soTimeout = 0
        val o = s.getOutputStream(); val i = DataInputStream(s.getInputStream())
        val key = Base64.getEncoder().encodeToString(Crypto.randomBytes(16))
        val path = (u.rawPath?.ifEmpty { "/" } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
        o.write("GET $path HTTP/1.1\r\nHost: ${u.host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n".toByteArray())
        o.flush()
        val status = line(i)
        require(status.contains(" 101")) { "no upgrade: $status" }
        while (line(i).isNotEmpty()) Unit
        socket = s; out = o; open = true
        Thread { read(i) }.apply { isDaemon = true }.start()
    }

    fun send(text: String) = frame(0x1, text.toByteArray())

    fun close() { runCatching { frame(0x8, ByteArray(0)) }; runCatching { socket?.close() }; open = false }

    private fun line(i: DataInputStream) = buildString { while (true) { val c = i.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) append(c.toChar()) } }

    // Client frames are always masked (RFC 6455 §5.3).
    private fun frame(op: Int, data: ByteArray) {
        val o = out ?: return
        val mask = Crypto.randomBytes(4)
        val head = java.io.ByteArrayOutputStream()
        head.write(0x80 or op)
        when {
            data.size < 126 -> head.write(0x80 or data.size)
            data.size < 65_536 -> { head.write(0x80 or 126); head.write(data.size shr 8); head.write(data.size and 0xff) }
            else -> { head.write(0x80 or 127); for (k in 7 downTo 0) head.write(((data.size.toLong() shr (8 * k)) and 0xff).toInt()) }
        }
        head.write(mask)
        val masked = ByteArray(data.size) { (data[it].toInt() xor mask[it % 4].toInt()).toByte() }
        synchronized(o) { o.write(head.toByteArray() + masked); o.flush() }
    }

    private fun read(i: DataInputStream) {
        val text = java.io.ByteArrayOutputStream()
        runCatching {
            while (true) {
                val b0 = i.readUnsignedByte(); val b1 = i.readUnsignedByte()
                var len = (b1 and 0x7f).toLong()
                if (len == 126L) len = i.readUnsignedShort().toLong() else if (len == 127L) len = i.readLong()
                val mask = if (b1 and 0x80 != 0) ByteArray(4).also { i.readFully(it) } else null
                require(len <= MAX_MESSAGE) { "too big" }
                val data = ByteArray(len.toInt()).also { i.readFully(it) }
                if (mask != null) for (k in data.indices) data[k] = (data[k].toInt() xor mask[k % 4].toInt()).toByte()
                when (b0 and 0x0f) {
                    0x1, 0x0 -> { text.write(data); if (b0 and 0x80 != 0) { val t = text.toString(Charsets.UTF_8.name()); text.reset(); runCatching { onText(t) } } }
                    0x8 -> break
                    0x9 -> frame(0xA, data) // ping → pong
                }
            }
        }
        open = false
        runCatching { socket?.close() }
        onClose()
    }

    companion object { const val MAX_MESSAGE = 1L shl 20 }
}

/**
 * El puente por Internet sobre Nostr (Discovery & Routing §11.2): no server of ours. A phone that lends Internet publishes
 * on several public Nostr relays "I am a bridge in this manzana" (signed with its CHAMULLO key too, so nobody registers in
 * its name) and listens for frames addressed to it. Frames go sealed, as base64 in events that expire in an hour.
 * Like [RelayBridge], it never blocks the node: the directory answers from a cache, frames leave on their own.
 */
class NostrBridge(private val relays: List<String>, val identity: Identity, private val onFrame: (ByteArray) -> Unit) : Bridge {
    private val myPub = Nostr.publicKey(identity).toHex()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private class Entry(val nostr: String, val node: ByteArray, val zones: Set<String>, val at: Long)
    private val directory = ConcurrentHashMap<String, Entry>() // chamullo id hex → its bridge entry
    private val watching = ConcurrentHashMap.newKeySet<String>() // zones I asked the relays about
    private val seen = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var zone: Zone? = null
    @Volatile private var running = false
    @Volatile var lastError: String? = null; private set
    @Volatile var sentBytes = 0L; private set
    private val since = System.currentTimeMillis() / 1000

    /** Where I am: the relays only learn my manzana and barrio. */
    fun place(cell: Zone) { zone = cell.up(Zone.MANZANA) }

    fun connected(): Int = sockets.values.count { it.open }

    fun start() {
        running = true
        Thread {
            while (running) {
                for (url in relays) if (sockets[url]?.open != true) runCatching { connect(url) }.onFailure { lastError = "$url: ${it.message}" }
                runCatching { register() }
                runCatching { Thread.sleep(REGISTER_MS) }
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() { running = false; sockets.values.forEach { it.close() }; sockets.clear() }

    override fun peersIn(zone: Zone): List<ByteArray> {
        val want = listOf(zone) + if (zone.level < Zone.BARRIO) listOf(zone.up(Zone.BARRIO)) else emptyList()
        for (z in want) if (watching.add(z.encode().toHex())) broadcast(subscribe(z))
        val now = System.currentTimeMillis()
        fun inside(z: Zone) = directory.values.filter { z.encode().toHex() in it.zones && now - it.at < FRESH_MS && it.nostr != myPub }.map { it.node }
        return inside(zone).ifEmpty { if (zone.level < Zone.BARRIO) inside(zone.up(Zone.BARRIO)) else emptyList() }
    }

    override fun send(to: ByteArray, frame: ByteArray) {
        val e = directory[to.toHex()] ?: return
        val now = System.currentTimeMillis() / 1000
        val ev = Nostr.event(identity, KIND_FRAME, listOf(listOf("p", e.nostr), listOf("t", TAG), listOf("expiration", (now + 3600).toString())),
            Base64.getEncoder().encodeToString(frame), now)
        sentBytes += frame.size
        broadcast("[\"EVENT\",${ev.toJson()}]")
    }

    private fun connect(url: String) {
        val ws = WebSocket(url, { onMessage(it) }, { })
        ws.connect()
        sockets[url] = ws
        ws.send("[\"REQ\",\"inbox\",{\"kinds\":[$KIND_FRAME],\"#p\":[${Json.str(myPub)}],\"since\":${since - 600}}]")
        for (z in watching) ws.send(subscribeHex(z))
    }

    // "I am a bridge here": manzana and barrio, my CHAMULLO id, and my CHAMULLO signature over my Nostr key.
    private fun register() {
        val z = zone ?: return
        val now = System.currentTimeMillis() / 1000
        val ev = Nostr.event(identity, KIND_DIRECTORY, listOf(
            listOf("d", "chamullo-bridge"), listOf("t", TAG), listOf("g", z.encode().toHex()), listOf("g", z.up(Zone.BARRIO).encode().toHex()),
            listOf("n", identity.nodeId.toHex()), listOf("expiration", (now + FRESH_MS / 1000).toString())
        ), identity.sign("NOSTR", hex(myPub)).toHex(), now)
        broadcast("[\"EVENT\",${ev.toJson()}]")
    }

    private fun subscribe(z: Zone) = subscribeHex(z.encode().toHex())

    private fun subscribeHex(zHex: String) =
        "[\"REQ\",\"dir-$zHex\",{\"kinds\":[$KIND_DIRECTORY],\"#g\":[${Json.str(zHex)}],\"#t\":[\"$TAG\"],\"since\":${System.currentTimeMillis() / 1000 - FRESH_MS / 1000}}]"

    private fun broadcast(msg: String) { for (ws in sockets.values) if (ws.open) runCatching { ws.send(msg) } }

    private fun onMessage(text: String) {
        val m = runCatching { Json.parse(text) as List<*> }.getOrNull() ?: return
        if (m.size < 3 || m[0] != "EVENT") return
        val e = runCatching { Nostr.Event.fromJson(m[2] as Map<*, *>) }.getOrNull() ?: return
        if (!seen.add(e.id)) return // the same event from several relays
        while (seen.size > 5_000) seen.remove(seen.first())
        when (e.kind) {
            KIND_FRAME -> if (e.pubkey != myPub && myPub in e.tag("p") && e.verify())
                runCatching { onFrame(Base64.getDecoder().decode(e.content)) }
            KIND_DIRECTORY -> {
                val node = e.tag("n").firstOrNull()?.let { runCatching { hex(it) }.getOrNull() } ?: return
                // Bound both ways: the Nostr key signed the event, and the CHAMULLO key signed the Nostr key.
                if (!e.verify() || !Identity.verify(node, "NOSTR", hex(e.pubkey), runCatching { hex(e.content) }.getOrDefault(ByteArray(0)))) return
                val prev = directory[node.toHex()]
                if (prev == null || prev.at <= e.createdAt * 1000) directory[node.toHex()] = Entry(e.pubkey, node, e.tag("g").toSet(), e.createdAt * 1000)
            }
        }
    }

    companion object {
        /** NIP-78 app data (parameterized replaceable): the latest "I am a bridge here" of each phone. */
        const val KIND_DIRECTORY = 30078
        /** A sealed CHAMULLO frame for one bridge; it expires in an hour (NIP-40). */
        const val KIND_FRAME = 4078
        const val TAG = "chamullo"
        const val REGISTER_MS = 10 * 60_000L
        const val FRESH_MS = 30 * 60_000L

        /** Public Nostr relays nobody owns: the bridge publishes on all of them, so one down does not matter. */
        // Checked by hand on 2026-10-06 (read-only): they answer and their events verify with this code.
        val DEFAULT_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://relay.snort.social", "wss://offchain.pub")
    }
}
