package ar.chamullo.sim

import kotlin.math.ln

/**
 * Economy & Governance (spec 04), computed from the carries the twin observed:
 * - scarcity: where paths are few a carry pays more, between a floor and a ceiling (§5.2);
 * - diversity: what counts is the distinct people served; repeating the same pair yields less and less (§5.1);
 * - the court: king and nobles by score, per village and national, computed by a public rule, never appointed (§8);
 * - a fixed daily bag shared by score, so raising scores never prints extra candies (§4.2).
 */
object Economy {
    const val FLOOR = 0.5
    const val CEILING = 3.0
    private const val K = 3.0

    class Court(val king: String?, val nobles: List<String>, val scores: Map<String, Double>)

    fun scarcity(alternatives: Int): Double = (K / (1 + alternatives)).coerceIn(FLOOR, CEILING)

    fun scores(carries: List<World.Carry>): Map<String, Double> =
        carries.groupBy { it.carrier }.mapValues { (_, mine) ->
            mine.groupBy { it.origin to it.destination }.values.sumOf { pair ->
                (1 + ln(pair.size.toDouble()) / ln(2.0)) * pair.map { scarcity(it.alternatives) }.average()
            }
        }

    private fun court(carries: List<World.Carry>, nobles: Int): Court {
        val s = scores(carries)
        val ranked = s.entries.sortedByDescending { it.value }.map { it.key }
        return Court(ranked.firstOrNull(), ranked.drop(1).take(nobles), s)
    }

    fun courts(carries: List<World.Carry>, nobles: Int = 20): Map<String, Court> =
        carries.groupBy { it.village }.mapValues { (_, c) -> court(c, nobles) }

    fun national(carries: List<World.Carry>, nobles: Int = 20): Court = court(carries, nobles)

    fun dailyShare(scores: Map<String, Double>, bag: Int = 1000): Map<String, Int> {
        val total = scores.values.sum()
        return if (total <= 0) scores.mapValues { 0 } else scores.mapValues { (_, v) -> Math.round(bag * v / total).toInt() }
    }
}
