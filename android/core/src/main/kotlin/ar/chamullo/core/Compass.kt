package ar.chamullo.core

import kotlin.math.ceil

/**
 * La brújula y el río (Discovery & Routing §6, exact rules in §10.4): what a carrier does with a letter in its pocket,
 * from where it is, where the letter goes and who is around. Pure: the same inputs always give the same plan.
 */
object Compass {
    const val K = 2
    // Paid priority opens one stream more (Economy & Governance §6.1).
    const val K_PRIORITY = 3
    // Calibrated with the twin: as deep as the v0.1 eco reached, but only inside the destination barrio.
    const val LOCAL_TTL = 8
    const val LAKE_BUDGET = 3
    const val PLAUSIBLE_M = 400.0
    const val PROGRESS_MIN_M = 10.0
    const val HOP_M = 40.0
    // A neighbor that did not take a letter is left out for a while, not forever: on islands, people come and go.
    const val REFUSED_MS = 30_000L

    /** A neighbor as the compass sees it: its id, the cell it declared (signed) and whether it is stable. */
    class Peer(val id: ByteArray, val cell: Zone?, val stable: Boolean)

    sealed interface Plan {
        /** Shout to everyone. [localTtl]: what is left of the local eco after this shout; null for the v0.1 eco. */
        data class Eco(val localTtl: Int?) : Plan
        /** Offer to these neighbors, best first; [alternatives]: stable neighbors that offered progress. */
        class River(val takers: List<Peer>, val alternatives: Int) : Plan
        /** No progress: offer to one neighbor around the obstacle; [detour] goes in the copy. */
        data class Lake(val taker: Peer, val detour: Int) : Plan
        /** Keep it and decide again when someone new shows up. */
        data object Hold : Plan
        /** The local eco is spent here. */
        data object Stop : Plan
    }

    fun plan(
        me: Zone?, dest: Zone?, localTtl: Int?, detour: Int, peers: List<Peer>, from: ByteArray?,
        origin: Boolean, refused: Map<String, Long> = emptyMap(), now: Long = 0, priority: Boolean = false
    ): Plan {
        if (dest == null || me == null) return Plan.Eco(null)
        if (dest.contains(me)) {
            val left = localTtl ?: LOCAL_TTL
            return if (left <= 0) Plan.Stop else Plan.Eco(left - 1)
        }
        val mine = me.distanceTo(dest)
        val usable = peers.filter { p ->
            p.cell != null && p.cell != me && (refused[p.id.toHex()]?.let { now - it < REFUSED_MS } != true) && me.distanceTo(p.cell) <= PLAUSIBLE_M &&
                (from == null || !p.id.contentEquals(from)) // never back to whoever gave it to me
        }
        val progress = usable.map { it to mine - it.cell!!.distanceTo(dest) }.filter { it.second >= PROGRESS_MIN_M }
        if (progress.isNotEmpty()) {
            val ranked = progress.sortedWith(compareByDescending<Pair<Peer, Double>> { (p, gain) -> gain * if (p.stable) 1.0 else 0.5 }
                .thenBy { it.first.id.toHex() })
            return Plan.River(ranked.take(if (origin) (if (priority) K_PRIORITY else K) else 1).map { it.first }, progress.count { it.first.stable })
        }
        // El lago: widen to ±90° of the bearing, then go around keeping the right hand on the wall.
        val around = usable
        if (around.isEmpty() || detour >= LAKE_BUDGET) return Plan.Hold
        val bearing = me.bearingTo(dest)
        fun clockwise(p: Peer) = (me.bearingTo(p.cell!!) - bearing + 360) % 360
        fun off(p: Peer) = clockwise(p).let { if (it > 180) 360 - it else it }
        val wide = around.filter { off(it) <= 90.0 }.minWithOrNull(compareBy<Peer> { off(it) }.thenBy { it.id.toHex() })
        val next = wide ?: around.minWithOrNull(compareBy<Peer> { clockwise(it) }.thenBy { it.id.toHex() })!!
        return Plan.Lake(next, detour + 1)
    }

    /** Discovery & Routing §10.3: enough hops to walk there and then search the barrio. */
    fun maxHops(me: Zone?, dest: Zone?): Int {
        if (me == null || dest == null) return 8
        val d = if (dest.contains(me)) 0.0 else me.distanceTo(dest)
        return (ceil(d / HOP_M).toInt() + LOCAL_TTL + 4).coerceIn(8, 255)
    }
}
