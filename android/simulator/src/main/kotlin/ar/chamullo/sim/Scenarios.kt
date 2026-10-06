package ar.chamullo.sim

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class Village(val name: String, val cx: Double, val radius: Double, val phones: Int, val walkers: Int = 0, val cy: Double = 0.0)

class Scenario(val key: String, val title: String, val story: String, val villages: List<Village>, val letters: Int, val minutes: Int, val crossOnly: Boolean = false)

/** The scenarios shared by the report (Main) and the live lab (Live). */
object Scenarios {
    val all = listOf(
        Scenario("plaza", "Una plaza llena", "20 celulares en 60 m. Más de 7 no entran en una isla: se forman varias y se conectan por ferry.",
            listOf(Village("Plaza", 0.0, 30.0, 20)), letters = 30, minutes = 12),
        Scenario("dos", "Dos pueblos que se tocan por el borde", "Pueblos de 8 celulares, bordes a ~70 m. Las cartas cruzan solo con el ferry.",
            listOf(Village("Norte", 0.0, 15.0, 8), Village("Sur", 100.0, 15.0, 8)), letters = 20, minutes = 15, crossOnly = true),
        Scenario("ruta", "Ruta de tres pueblos", "Norte y Sur no se ven: todo pasa por el pueblo del medio, y su gente cobra por escasez.",
            listOf(Village("Norte", 0.0, 15.0, 6), Village("Medio", 95.0, 15.0, 4), Village("Sur", 190.0, 15.0, 6)), letters = 20, minutes = 20, crossOnly = true),
        Scenario("viajeros", "Pueblos con viajeros", "Dos pueblos lejos (200 m); tres personas caminan entre ellos y llevan las cartas en el bolsillo.",
            listOf(Village("Este", 0.0, 15.0, 6, walkers = 2), Village("Oeste", 200.0, 15.0, 6, walkers = 1)), letters = 20, minutes = 20, crossOnly = true),
        Scenario("ciudad", "Ciudad de 1000", "1000 celulares en ocho barrios de 125 que se tocan por el borde, con 40 personas caminando.",
            (0 until 8).map { i -> Village("Barrio-${i + 1}", 100.0 + 200.0 * (i % 4), 100.0, 125, walkers = 5, cy = 100.0 + 200.0 * (i / 4)) },
            letters = 120, minutes = 20)
    )

    fun build(s: Scenario, seed: Long): World {
        val w = World(seed = seed, areaM = (s.villages.maxOf { it.cx + it.radius } + 40).coerceAtLeast(120.0))
        val r = Random(seed)
        for (v in s.villages) repeat(v.phones) { i ->
            val a = r.nextDouble() * 2 * Math.PI; val d = sqrt(r.nextDouble()) * v.radius
            w.addPhone("${v.name}-${i + 1}", v.cx + cos(a) * d, v.cy + sin(a) * d, v.name, if (i < v.walkers) 1.4 else 0.0)
        }
        w.befriendAll()
        return w
    }
}
