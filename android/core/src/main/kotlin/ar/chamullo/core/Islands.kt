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
    val roster: List<String>
) {
    /** DNS-SD TXT record: short keys, fits the ~255 bytes a Wi-Fi Direct service record allows. */
    fun toTxt(): Map<String, String> = buildMap {
        put("i", nodeId); put("n", name.take(12)); put("l", island); put("h", if (host) "1" else "0")
        if (host) { put("s", ssid); put("p", passphrase); put("r", roster.joinToString(",") { it.take(8) }) }
    }

    companion object {
        fun fromTxt(t: Map<String, String>): Cartel? {
            val id = t["i"] ?: return null
            val island = t["l"] ?: return null
            val host = t["h"] == "1"
            return Cartel(id, t["n"] ?: "", island, t["s"] ?: "", t["p"] ?: "", host,
                t["r"]?.split(',')?.filter { it.isNotEmpty() } ?: emptyList())
        }
    }
}

/** [ferriedTurn]: the ferry turn in which this phone already made its trip (one trip per turn). */
data class IslandState(val island: String?, val host: Boolean, val members: List<String>, val ferriedTurn: Long = -1)

sealed interface IslandAction {
    data object Stay : IslandAction
    data object Host : IslandAction
    data class Join(val island: String, val ssid: String, val passphrase: String) : IslandAction
    data class Ferry(val island: String, val ssid: String, val passphrase: String) : IslandAction
}

object Islands {
    const val MAX_MEMBERS = 7
    // One seat stays free for visiting ferries: newcomers join only while there are two or more seats left.
    const val FERRY_SEATS = 1
    const val FERRY_TURN_MS = 60_000L

    fun turnOf(now: Long) = now / FERRY_TURN_MS

    fun decide(me: String, state: IslandState, carteles: List<Cartel>, now: Long): IslandAction {
        val hosts = carteles.filter { it.host && it.island != state.island && it.ssid.isNotEmpty() }
        // People of another island whose host nobody here can see: the border between two islands.
        val visibleHosts = carteles.filter { it.host }.map { it.island }.toSet()
        val strangers = carteles.filter { !it.host && it.island.isNotEmpty() && it.island != state.island && it.island !in visibleHosts }
        val withRoom = hosts.filter { it.roster.size < MAX_MEMBERS - FERRY_SEATS }
        val best = withRoom.maxWithOrNull(compareBy<Cartel> { it.roster.size }.thenByDescending { it.island })

        // Not on any island: join the biggest one with room, or found my own.
        if (state.island == null) return best?.let { IslandAction.Join(it.island, it.ssid, it.passphrase) } ?: IslandAction.Host

        if (state.host) {
            // A lonely host merges into a neighbor: a bigger island, or another lonely one with a smaller id.
            if (state.members.isNotEmpty() || best == null) return IslandAction.Stay
            if (strangers.isNotEmpty()) return IslandAction.Stay // a bridge keeps its post at the border
            val merge = best.roster.isNotEmpty() || best.island < me
            return if (merge) IslandAction.Join(best.island, best.ssid, best.passphrase) else IslandAction.Stay
        }

        // A member: in each turn one member of the roster is the ferry to a neighboring island.
        // A bridge: I see people of another island but not its host, so I found a small island at the border.
        val other = hosts.filter { it.roster.size < MAX_MEMBERS }.minByOrNull { it.island } ?: return if (strangers.isNotEmpty()) IslandAction.Host else IslandAction.Stay
        val roster = state.members.sorted()
        if (roster.isEmpty()) return IslandAction.Stay
        if (state.ferriedTurn == turnOf(now)) return IslandAction.Stay // one trip per turn
        val turn = (turnOf(now) % roster.size).toInt()
        return if (roster[turn].take(8) == me.take(8)) IslandAction.Ferry(other.island, other.ssid, other.passphrase) else IslandAction.Stay
    }
}
