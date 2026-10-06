// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.app

import ar.chamullo.core.CallMsg
import ar.chamullo.core.Packet
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The voice's own lane (Camino y Carretera §7.1): UDP datagrams straight between the two phones of a call, inside the
 * island's network. A lost piece is skipped instead of holding back the ones behind it, as the pipe (TCP) would do.
 * Each datagram is a whole CallMsg frame, sealed like any other.
 */
class MediaLane(private val onCall: (CallMsg) -> Unit) {
    @Volatile private var socket: DatagramSocket? = null

    fun start() {
        if (socket != null) return
        val s = runCatching { DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(PORT)) } }
            .onFailure { FieldLog.add("LLAMADA", "no pude abrir el carril de voz: ${it.message}") }.getOrNull() ?: return
        socket = s
        Thread {
            val buf = ByteArray(MAX_DATAGRAM)
            while (!s.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                if (runCatching { s.receive(p) }.isFailure) break
                (Packet.parseOrNull(buf.copyOf(p.length)) as? CallMsg)?.let { runCatching { onCall(it) } }
            }
        }.apply { isDaemon = true; name = "call-udp"; priority = Thread.MAX_PRIORITY }.start()
    }

    fun send(frame: ByteArray, ip: ByteArray) {
        val s = socket ?: return
        if (frame.size > MAX_DATAGRAM) return
        runCatching { s.send(DatagramPacket(frame, frame.size, InetAddress.getByAddress(ip), PORT)) }
    }

    fun stop() { runCatching { socket?.close() }; socket = null }

    companion object {
        const val PORT = 47_476
        /** One Wi-Fi frame, so a datagram is never split: voice pieces fit; the picture goes through the pipe. */
        const val MAX_DATAGRAM = 1_400
    }
}
