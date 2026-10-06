package ar.chamullo.sim

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.random.Random

/**
 * The CHAMULLO lab, live: the digital twin (the real core on simulated islands) runs here and the lab page in the
 * browser shows it as it happens. Usage: ./gradlew :simulator:live  →  http://localhost:8787
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 8787
    val live = LiveRun()
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/") { ex ->
        val page = LiveRun::class.java.getResource("/live.html")!!.readBytes()
        reply(ex, "text/html; charset=utf-8", page)
    }
    server.createContext("/state") { ex -> reply(ex, "application/json; charset=utf-8", live.state().toByteArray()) }
    server.createContext("/control") { ex ->
        val q = (ex.requestURI.rawQuery ?: "").split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        live.control(q)
        reply(ex, "application/json; charset=utf-8", live.state().toByteArray())
    }
    server.start()
    println("Laboratorio CHAMULLO en vivo: http://localhost:$port")
    live.loop()
}

private fun reply(ex: HttpExchange, type: String, body: ByteArray) {
    ex.responseHeaders.add("Content-Type", type)
    ex.responseHeaders.add("Cache-Control", "no-store")
    ex.sendResponseHeaders(200, body.size.toLong())
    ex.responseBody.use { it.write(body) }
}

class LiveRun {
    private val lock = Any()
    private var scenario = Scenarios.all.first()
    private var seed = 7L
    private var world = Scenarios.build(scenario, seed)
    private var random = Random(seed)
    @Volatile private var speed = 5
    @Volatile private var paused = false
    private var nextLetterAt = 90_000L

    fun loop() {
        while (true) {
            Thread.sleep(50)
            if (paused) continue
            synchronized(lock) {
                world.run(50L * speed)
                if (world.now >= nextLetterAt) { letter(); nextLetterAt = world.now + 8_000 }
            }
        }
    }

    // Random letters, as the scenario says: anywhere, or from the first village to the last.
    private fun letter() {
        val villages = scenario.villages.map { it.name }
        val (fv, tv) = if (scenario.crossOnly) villages.first() to villages.last() else villages.random(random) to villages.random(random)
        val from = world.phonesOf(fv).random(random)
        val to = world.phonesOf(tv).filter { it != from }.randomOrNull(random) ?: return
        world.send(from, to, "carta")
    }

    fun control(q: Map<String, String>) = synchronized(lock) {
        q["speed"]?.toIntOrNull()?.let { speed = it.coerceIn(1, 50) }
        q["pause"]?.let { paused = it == "1" }
        q["letter"]?.let { letter() }
        q["scenario"]?.let { key -> Scenarios.all.firstOrNull { it.key == key }?.let { scenario = it; restart() } }
        q["reset"]?.let { restart() }
    }

    private fun restart() {
        world = Scenarios.build(scenario, seed)
        random = Random(seed)
        nextLetterAt = 90_000L
    }

    fun state(): String = synchronized(lock) {
        val list = Scenarios.all.joinToString(",") { "{\"key\":\"${it.key}\",\"title\":\"${it.title}\"}" }
        "{\"scenario\":\"${scenario.key}\",\"title\":\"${scenario.title}\",\"story\":\"${scenario.story}\",\"speed\":$speed,\"paused\":$paused,\"scenarios\":[$list],\"world\":${world.snapshotJson()}}"
    }
}
