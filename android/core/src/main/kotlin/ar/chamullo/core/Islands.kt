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
    val cell: Zone? = null,
    /** A member that is also a fixed bridge (§6.2) says to which island: one bridge per pair of islands. */
    val bridgeTo: String = ""
) {
    /** DNS-SD TXT record: short keys, fits the ~255 bytes a Wi-Fi Direct service record allows. */
    fun toTxt(): Map<String, String> = buildMap {
        put("i", nodeId); put("n", name.take(12)); put("l", island); put("h", if (host) "1" else "0")
        if (host) { put("s", ssid); put("p", passphrase); put("r", roster.joinToString(",") { it.take(8) }); cell?.let { put("c", it.encode().toHex()) } }
        if (bridgeTo.isNotEmpty()) put("b", bridgeTo)
    }

    companion object {
        fun fromTxt(t: Map<String, String>): Cartel? {
            val id = t["i"] ?: return null
            val island = t["l"] ?: return null
            val host = t["h"] == "1"
            return Cartel(id, t["n"] ?: "", island, t["s"] ?: "", t["p"] ?: "", host,
                t["r"]?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(),
                t["c"]?.let { c -> runCatching { Zone.decode(hex(c)) }.getOrNull() }, t["b"] ?: "")
        }
    }
}

/** [ferriedTurn]: the ferry turn in which this phone already made its trip (one trip per turn). */
data class IslandState(
    val island: String?, val host: Boolean, val members: List<String>, val ferriedTurn: Long = -1,
    /** The island I also stand in as a fixed bridge, and whether this phone can hold two Wi-Fi connections at once. */
    val bridging: String? = null, val canBridge: Boolean = false
)

sealed interface IslandAction {
    data object Stay : IslandAction
    data object Host : IslandAction
    data class Join(val island: String, val ssid: String, val passphrase: String) : IslandAction
    /** [cell]: where the target island is, the heading the ferry announces while letters board it. */
    data class Ferry(val island: String, val ssid: String, val passphrase: String, val cell: Zone? = null) : IslandAction
    /** Stay in my island and also join that one (Camino y Carretera §6.2): a fixed bridge. */
    data class Bridge(val island: String, val ssid: String, val passphrase: String) : IslandAction
    /** Leave the second island: another member already bridges that pair. */
    data object Unbridge : IslandAction
}

object Islands {
    const val MAX_MEMBERS = 7
    // One seat stays free for visiting ferries: newcomers join only while there are two or more seats left.
    const val FERRY_SEATS = 1
    const val FERRY_TURN_MS = 60_000L
    // Before sailing, the ferry announces its heading and waits for the letters that go that way (Discovery & Routing §10.6).
    const val FERRY_BOARDING_MS = 3_000L

    fun turnOf(now: Long) = now / FERRY_TURN_MS

    /* Islands seen in the Wi-Fi list (Camino y Carretera §6.4): a Wi-Fi Direct group is also a plain Wi-Fi network named
     * DIRECT-CH-xxxxxx. Its key comes from that name, so any CHAMULLO joins it without a cartel. The island is only the
     * air: letters stay sealed and signed, and the pipe to the host has its own handshake. */
    const val SSID_PREFIX = "DIRECT-CH-"

    /** An island is named by the first six hex digits of its host's id: the same in the cartel and in the Wi-Fi list. */
    fun islandId(nodeId: String) = nodeId.take(6)

    fun ssidOf(nodeId: String) = SSID_PREFIX + islandId(nodeId)

    fun passphraseFor(ssid: String): String = Crypto.hash("CHAMULLO/1/ISLAND".toByteArray() + 0.toByte() + ssid.toByteArray()).toHex().take(20)

    /** A CHAMULLO island found in the Wi-Fi list, as a host's cartel; null for any other network. */
    fun fromScan(ssid: String): Cartel? {
        if (!ssid.startsWith(SSID_PREFIX)) return null
        val id = ssid.removePrefix(SSID_PREFIX)
        if (id.length != 6 || id.any { it !in "0123456789abcdef" }) return null
        return Cartel(id, "", id, ssid, passphraseFor(ssid), host = true, roster = emptyList())
    }

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

        // El puente fijo (§6.2): a member that can hold two connections stays in its island and joins a neighbor too.
        // One per pair of islands: if two started at once, the bigger id lets go. A bridge does not need to ferry.
        if (state.bridging != null) {
            val rival = carteles.any { !it.host && it.island == state.island && it.bridgeTo == state.bridging && it.nodeId < me }
            return if (rival) IslandAction.Unbridge else IslandAction.Stay
        }
        if (state.canBridge) {
            val taken = carteles.filter { !it.host && it.island == state.island && it.bridgeTo.isNotEmpty() }.map { it.bridgeTo }.toSet()
            hosts.filter { it.roster.size < MAX_MEMBERS - FERRY_SEATS && it.island !in taken }.minByOrNull { it.island }
                ?.let { return IslandAction.Bridge(it.island, it.ssid, it.passphrase) }
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
