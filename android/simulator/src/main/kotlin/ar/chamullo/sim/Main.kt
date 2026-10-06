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
    for (s in Scenarios.all) out.append(run(s))
    val file = File("../../docs/SIMULACION.md")
    file.writeText(out.toString())
    println(out)
    println("Informe escrito en ${file.canonicalPath}")
}

private fun run(s: Scenario): String {
    val r = Random(7)
    val w = Scenarios.build(s, seed = 7)
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
    sb.appendLine("| Cartas en bolsillos al final | ${w.history().lastOrNull()?.pockets ?: 0} |")
    if (w.internetBytes() > 0) {
        val share = Economy.dailyShare(Economy.national(rep.carries).scores)
        val kb = (w.airLetterBytes + w.internetBytes()) / 1024.0
        sb.appendLine("| Por Internet (el puente) | ${w.internetBytes() / 1024} KB, de ${w.bridges().size} puentes |")
        sb.appendLine("| Lucas de los puentes (bolsa diaria de 1000) | ${w.bridges().sumOf { share[it] ?: 0 }} |")
        sb.appendLine("| Precio de la información | ${f(1000 / kb)} Lucas por KB llevado (${kb.toInt()} KB entre aire e Internet) |")
    }
    w.drops().takeIf { it.isNotEmpty() }?.let { d -> sb.appendLine("| Copias soltadas, y por qué | ${d.entries.joinToString(", ") { "${it.key}: ${it.value}" }} |") }
    if (w.furthestLetterM() >= 0) sb.appendLine("| La carta más avanzada que sigue en camino | a ${w.furthestLetterM()} m del oeste |")
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
        sb.appendLine("Reparto de una bolsa diaria de 1000 Lucas: " + bag.joinToString(", ") { "${it.key} ${it.value}" } + ".")
        sb.appendLine()
    }
    sb.appendLine("_Tiempo de cómputo: ${f(secs)} s._")
    sb.appendLine()
    return sb.toString()
}

private fun pct(x: Double) = String.format(Locale.ROOT, "%.0f %%", x * 100)
private fun f(x: Double) = String.format(Locale.ROOT, "%.1f", x)
