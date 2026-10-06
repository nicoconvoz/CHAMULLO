package ar.chamullo.core

/**
 * Islas (Camino y Carretera v0.2, Capitán's design): the phones near each other share one Wi-Fi Direct group, an
 * island. Newcomers join an island with room instead of founding one; two lonely hosts merge; members take turns as
 * the ferry that leaves the island for a while, delivers what it carries to a neighboring island and comes back.
 */

/** What a phone hangs on its Wi-Fi Direct service record so others see it without connecting. */
data class Cartel(
    val nodeId: String,
    val name: String,
    val island: String,
    val ssid: String,
    val passphrase: String,
    val host: Boolean,
    val roster: List<String>,
    /** The island's cell (Camino y Carretera §6.1): where a ferry that sails there is heading. Only hosts hang it. */
    val cell: Zone? = null
) {
    /** DNS-SD TXT record: short keys, fits the ~255 bytes a Wi-Fi Direct service record allows. */
    fun toTxt(): Map<String, String> = buildMap {
        put("i", nodeId); put("n", name.take(12)); put("l", island); put("h", if (host) "1" else "0")
        if (host) { put("s", ssid); put("p", passphrase); put("r", roster.joinToString(",") { it.take(8) }); cell?.let { put("c", it.encode().toHex()) } }
    }

    companion object {
        fun fromTxt(t: Map<String, String>): Cartel? {
            val id = t["i"] ?: return null
            val island = t["l"] ?: return null
            val host = t["h"] == "1"
            return Cartel(id, t["n"] ?: "", island, t["s"] ?: "", t["p"] ?: "", host,
                t["r"]?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(),
                t["c"]?.let { c -> runCatching { Zone.decode(hex(c)) }.getOrNull() })
        }
    }
}

/** [ferriedTurn]: the ferry turn in which this phone already made its trip (one trip per turn). */
data class IslandState(val island: String?, val host: Boolean, val members: List<String>, val ferriedTurn: Long = -1)

sealed interface IslandAction {
    data object Stay : IslandAction
    data object Host : IslandAction
    data class Join(val island: String, val ssid: String, val passphrase: String) : IslandAction
    /** [cell]: where the target island is, the heading the ferry announces while letters board it. */
    data class Ferry(val island: String, val ssid: String, val passphrase: String, val cell: Zone? = null) : IslandAction
}

object Islands {
    const val MAX_MEMBERS = 7
    // One seat stays free for visiting ferries: newcomers join only while there are two or more seats left.
    const val FERRY_SEATS = 1
    const val FERRY_TURN_MS = 60_000L
    // Before sailing, the ferry announces its heading and waits for the letters that go that way (Discovery & Routing §10.6).
    const val FERRY_BOARDING_MS = 3_000L

    fun turnOf(now: Long) = now / FERRY_TURN_MS

    /**
     * [myCell] and [stuck]: where I am and the destination zone of a letter I hold that nobody on my island gets closer
     * (Discovery & Routing §10.6). With them, I may be the ferry of need.
     */
    fun decide(me: String, state: IslandState, carteles: List<Cartel>, now: Long, myCell: Zone? = null, stuck: Zone? = null): IslandAction {
        val hosts = carteles.filter { it.host && it.island != state.island && it.ssid.isNotEmpty() }
        // People of another island whose host nobody here can see: the border between two islands.
        val visibleHosts = carteles.filter { it.host }.map { it.island }.toSet()
        val strangers = carteles.filter { !it.host && it.island.isNotEmpty() && it.island != state.island && it.island !in visibleHosts }
        val withRoom = hosts.filter { it.roster.size < MAX_MEMBERS - FERRY_SEATS }
        val best = withRoom.maxWithOrNull(compareBy<Cartel> { it.roster.size }.thenByDescending { it.island })

        // Not on any island: join the biggest one with room, or found my own.
        if (state.island == null) return best?.let { IslandAction.Join(it.island, it.ssid, it.passphrase) } ?: IslandAction.Host

        // The ferry of need: I hold letters nobody here gets closer, so I sail myself toward the island that does, once
        // per turn. A host only if it is alone: it never leaves its members without the air.
        if (stuck != null && myCell != null && state.ferriedTurn != turnOf(now) && (!state.host || state.members.isEmpty())) {
            val mine = myCell.distanceTo(stuck)
            hosts.filter { it.roster.size < MAX_MEMBERS && it.cell != null }
                .map { it to mine - it.cell!!.distanceTo(stuck) }
                .filter { it.second >= Compass.PROGRESS_MIN_M }
                .maxWithOrNull(compareBy<Pair<Cartel, Double>> { it.second }.thenByDescending { it.first.island })
                ?.let { (h, _) -> return IslandAction.Ferry(h.island, h.ssid, h.passphrase, h.cell) }
        }

        if (state.host) {
            // A lonely host merges into a neighbor: a bigger island, or another lonely one with a smaller id.
            if (state.members.isNotEmpty() || best == null) return IslandAction.Stay
            if (strangers.isNotEmpty()) return IslandAction.Stay // a bridge keeps its post at the border
            val merge = best.roster.isNotEmpty() || best.island < me
            return if (merge) IslandAction.Join(best.island, best.ssid, best.passphrase) else IslandAction.Stay
        }

        // A member: in each turn one member of the roster is the ferry to a neighboring island, a different one each turn.
        // A bridge: I see people of another island but not its host, so I found a small island at the border.
        val neighbors = hosts.filter { it.roster.size < MAX_MEMBERS }.sortedBy { it.island }
        if (neighbors.isEmpty()) return if (strangers.isNotEmpty()) IslandAction.Host else IslandAction.Stay
        val other = neighbors[(turnOf(now) % neighbors.size).toInt()]
        val roster = state.members.sorted()
        if (roster.isEmpty()) return IslandAction.Stay
        if (state.ferriedTurn == turnOf(now)) return IslandAction.Stay // one trip per turn
        val turn = (turnOf(now) % roster.size).toInt()
        return if (roster[turn].take(8) == me.take(8)) IslandAction.Ferry(other.island, other.ssid, other.passphrase, other.cell) else IslandAction.Stay
    }
}
