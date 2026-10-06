package ar.chamullo.sim

import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Runs the scenarios on the digital twin and writes docs/SIMULACION.md.
 * Usage: ./gradlew :simulator:run
 */
fun main() {
    val out = StringBuilder()
    out.appendLine("# CHAMULLO — Simulación con el gemelo digital")
    out.appendLine()
    out.appendLine("Generado por `android/simulator` (`./gradlew :simulator:run`). Corre **el núcleo real de la app** (Node, sobres, recibos, pagos, `Islands.decide`);")
    out.appendLine("lo simulado es la radio: alcance Wi-Fi 80 m, 3 s para sumarse a una isla, 30 ms por trama. Son números de diseño, no de campo.")
    out.appendLine()
    for (s in scenarios()) out.append(run(s))
    val file = File("../../docs/SIMULACION.md")
    file.writeText(out.toString())
    println(out)
    println("Informe escrito en ${file.canonicalPath}")
}

private class Village(val name: String, val cx: Double, val radius: Double, val phones: Int, val walkers: Int = 0)
private class Scenario(val title: String, val story: String, val villages: List<Village>, val letters: Int, val minutes: Int, val crossOnly: Boolean = false)

private fun scenarios() = listOf(
    Scenario("Una plaza llena", "20 celulares en 60 m. Más de 7 no entran en una isla: se forman varias y se conectan por ferry.",
        listOf(Village("Plaza", 0.0, 30.0, 20)), letters = 30, minutes = 12),
    Scenario("Dos pueblos que se tocan por el borde", "Pueblos de 8 celulares, bordes a ~70 m. Las cartas cruzan solo con el ferry.",
        listOf(Village("Norte", 0.0, 15.0, 8), Village("Sur", 100.0, 15.0, 8)), letters = 20, minutes = 15, crossOnly = true),
    Scenario("Ruta de tres pueblos", "Norte y Sur no se ven: todo pasa por el pueblo del medio, y su gente cobra por escasez.",
        listOf(Village("Norte", 0.0, 15.0, 6), Village("Medio", 95.0, 15.0, 4), Village("Sur", 190.0, 15.0, 6)), letters = 20, minutes = 20, crossOnly = true)
)

private fun run(s: Scenario): String {
    val w = World(seed = 7)
    val r = Random(7)
    for (v in s.villages) repeat(v.phones) { i ->
        val a = r.nextDouble() * 2 * Math.PI; val d = sqrt(r.nextDouble()) * v.radius
        w.addPhone("${v.name}-${i + 1}", v.cx + cos(a) * d, sin(a) * d, v.name, if (i < v.walkers) 1.2 else 0.0)
    }
    w.befriendAll()
    val t0 = System.currentTimeMillis()
    w.run(90_000) // islands form
    val formed = w.islands()
    val names = s.villages.associate { v -> v.name to w.phonesOf(v.name) }
    repeat(s.letters) {
        val (fromV, toV) = if (s.crossOnly) s.villages.first().name to s.villages.last().name
        else s.villages.random(r).name to s.villages.random(r).name
        val from = names.getValue(fromV).random(r); var to = names.getValue(toV).random(r)
        while (to == from) to = names.getValue(toV).random(r)
        w.send(from, to, "carta $it")
        w.run(10_000)
    }
    w.run(s.minutes * 60_000L - 90_000L - s.letters * 10_000L)
    val rep = w.report()
    val secs = (System.currentTimeMillis() - t0) / 1000.0

    val sb = StringBuilder()
    sb.appendLine("## ${s.title}")
    sb.appendLine()
    sb.appendLine(s.story)
    sb.appendLine()
    sb.appendLine("| Resultado | Valor |")
    sb.appendLine("|---|---|")
    sb.appendLine("| Islas formadas en 90 s | ${formed.size} (${formed.values.joinToString(" · ") { "${it.size} celulares" }}) |")
    sb.appendLine("| Cartas entregadas | **${rep.delivered} de ${rep.sent}** (${pct(rep.deliveryRatio)}) |")
    if (rep.crossSent > 0) sb.appendLine("| Entre islas distintas | ${rep.crossDelivered} de ${rep.crossSent} |")
    sb.appendLine("| Demora p50 / p95 | ${f(rep.latencyP50s)} s / ${f(rep.latencyP95s)} s |")
    sb.appendLine("| Viajes de ferry | ${rep.ferryTrips} |")
    sb.appendLine("| Caramelos cobrados (recibos verificados) | ${rep.candies.values.sum()} |")
    sb.appendLine()
    val courts = Economy.courts(rep.carries, nobles = 2)
    if (courts.isNotEmpty()) {
        sb.appendLine("**La Corte** (puntaje = diversidad × escasez, spec 04):")
        sb.appendLine()
        sb.appendLine("| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |")
        sb.appendLine("|---|---|---|---|")
        for ((village, c) in courts) sb.appendLine("| $village | ${c.king} | ${c.nobles.joinToString(", ").ifEmpty { "—" }} | ${f(c.scores[c.king] ?: 0.0)} |")
        val nat = Economy.national(rep.carries, nobles = 3)
        sb.appendLine("| **Nacional** | **${nat.king}** | ${nat.nobles.joinToString(", ")} | ${f(nat.scores[nat.king] ?: 0.0)} |")
        sb.appendLine()
        val bag = Economy.dailyShare(nat.scores, 1000).entries.sortedByDescending { it.value }.take(5)
        sb.appendLine("Reparto de una bolsa diaria de 1000 ICE: " + bag.joinToString(", ") { "${it.key} ${it.value}" } + ".")
        sb.appendLine()
    }
    sb.appendLine("_Tiempo de cómputo: ${f(secs)} s._")
    sb.appendLine()
    return sb.toString()
}

private fun pct(x: Double) = String.format(Locale.ROOT, "%.0f %%", x * 100)
private fun f(x: Double) = String.format(Locale.ROOT, "%.1f", x)
