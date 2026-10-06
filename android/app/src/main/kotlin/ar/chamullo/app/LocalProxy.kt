package ar.chamullo.app

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI

/**
 * The phone's door to the data tunnel (Discovery & Routing §11.3): an HTTP proxy on 127.0.0.1 that [TunnelVpn] hands to
 * every app. HTTPS goes as CONNECT (the tunnel never sees inside it); plain HTTP is passed on with "Connection: close",
 * one request per connection, so a connection never mixes two sites.
 */
class LocalProxy {
    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        val s = ServerSocket(PORT, 64, InetAddress.getByName("127.0.0.1")).also { server = it }
        Thread {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                Thread { runCatching { serve(client) }; runCatching { client.close() } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; name = "proxy" }.start()
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun serve(client: Socket) {
        val input = client.getInputStream()
        val head = readHead(input) ?: return
        val lines = String(head, Charsets.ISO_8859_1).split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 3) return
        val (method, target, version) = Triple(parts[0], parts[1], parts[2])
        val tunnel = Hub.tunnel
        if (method == "CONNECT") {
            val host = target.substringBeforeLast(':'); val port = target.substringAfterLast(':').toIntOrNull() ?: 443
            val stream = tunnel?.open(host, port) ?: return refuse(client, tunnel)
            client.getOutputStream().apply { write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); flush() }
            pipe(client, input, stream)
        } else {
            val uri = runCatching { URI(target) }.getOrNull()
            val host = uri?.host ?: return refuse(client, tunnel)
            val port = if (uri.port > 0) uri.port else 80
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val stream = tunnel?.open(host, port) ?: return refuse(client, tunnel)
            val headers = lines.drop(1).filter { it.isNotEmpty() && !it.startsWith("Proxy-", true) && !it.startsWith("Connection:", true) }
            val rewritten = (listOf("$method $path $version") + headers + "Connection: close" + "" + "").joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)
            stream.write(rewritten, 0, rewritten.size)
            pipe(client, input, stream)
        }
    }

    // Both ways at once: the app's bytes to the tunnel on another thread, the tunnel's bytes to the app here.
    private fun pipe(client: Socket, input: InputStream, stream: DataTunnel.BuyerStream) {
        val up = Thread {
            runCatching {
                val buf = ByteArray(16 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; stream.write(buf, 0, n) }
            }
            stream.end(true)
        }.apply { isDaemon = true; start() }
        runCatching {
            val out = client.getOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) { val n = stream.input.read(buf); if (n < 0) break; out.write(buf, 0, n); out.flush() }
        }
        stream.end(true)
        runCatching { client.close() }
        up.join(1_000)
    }

    private fun refuse(client: Socket, tunnel: DataTunnel?) {
        val why = when {
            tunnel == null -> "CHAMULLO no está encendido."
            tunnel.left() <= 0 -> "Se te acabaron los datos. Comprá más en la Tienda de Lucas."
            else -> "Nadie está prestando Internet en tu isla ahora. Probá en un rato."
        }
        val body = "<html><meta charset=utf-8><body style='font-family:sans-serif;padding:24px'><h2>CHAMULLO</h2><p>$why</p></body></html>".toByteArray()
        runCatching {
            client.getOutputStream().apply {
                write("HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(body); flush()
            }
        }
    }

    private fun readHead(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var last = 0
        while (out.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            last = (last shl 8) or b
            if (last == 0x0d0a0d0a) return out.toByteArray()
        }
        return null
    }

    companion object {
        const val PORT = 8118
        const val MAX_HEAD = 32 * 1024
    }
}
