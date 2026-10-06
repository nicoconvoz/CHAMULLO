// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Islas (Capitán's design): one Wi-Fi Direct group per island, newcomers join instead of founding, and members take
 * turns as the ferry that carries letters to a neighboring island.
 */
class IslandsTest {
    private fun host(id: String, members: List<String> = emptyList()) =
        Cartel(id, "H$id", island = id, ssid = "DIRECT-CH-$id", passphrase = "clave-$id", host = true, roster = members)

    private fun member(id: String, island: String) =
        Cartel(id, "M$id", island = island, ssid = "", passphrase = "", host = false, roster = emptyList())

    private val alone = IslandState(island = null, host = false, members = emptyList())

    @Test
    fun `alone and nobody around - found my own island`() {
        assertEquals(IslandAction.Host, Islands.decide("aaaa", alone, emptyList(), now = 0))
    }

    @Test
    fun `an island with room nearby - join it instead of founding one`() {
        val a = Islands.decide("zzzz", alone, listOf(host("bbbb", listOf("cccc"))), now = 0)
        assertEquals(IslandAction.Join("bbbb", "DIRECT-CH-bbbb", "clave-bbbb"), a)
    }

    @Test
    fun `the biggest island with room wins`() {
        val small = host("bbbb", listOf("c1"))
        val big = host("dddd", listOf("e1", "e2", "e3"))
        assertEquals("dddd", (Islands.decide("zzzz", alone, listOf(small, big), now = 0) as IslandAction.Join).island)
    }

    @Test
    fun `a full island is not joined - found a neighboring one`() {
        val full = host("bbbb", (1..Islands.MAX_MEMBERS).map { "m$it" })
        assertEquals(IslandAction.Host, Islands.decide("zzzz", alone, listOf(full), now = 0))
    }

    @Test
    fun `two lonely hosts merge - the one with the bigger id joins the other`() {
        val meLonely = IslandState(island = "zzzz", host = true, members = emptyList())
        assertEquals(IslandAction.Join("bbbb", "DIRECT-CH-bbbb", "clave-bbbb"), Islands.decide("zzzz", meLonely, listOf(host("bbbb")), now = 0))
        val otherLonely = IslandState(island = "bbbb", host = true, members = emptyList())
        assertEquals(IslandAction.Stay, Islands.decide("bbbb", otherLonely, listOf(host("zzzz")), now = 0))
    }

    @Test
    fun `a host with members never leaves its island`() {
        val me = IslandState(island = "aaaa", host = true, members = listOf("m1"))
        assertEquals(IslandAction.Stay, Islands.decide("aaaa", me, listOf(host("bbbb", listOf("x"))), now = 0))
    }

    @Test
    fun `members take turns as the ferry to a neighboring island`() {
        val roster = listOf("m1", "m2", "m3")
        val other = host("bbbb", listOf("x1"))
        val turns = (0 until 3).map { turn ->
            val now = turn * Islands.FERRY_TURN_MS
            roster.filter { me ->
                Islands.decide(me, IslandState("aaaa", host = false, members = roster), listOf(host("aaaa", roster), other), now) is IslandAction.Ferry
            }
        }
        assertEquals(listOf(listOf("m1"), listOf("m2"), listOf("m3")), turns, "one ferry per turn, rotating")
    }

    @Test
    fun `the ferry goes to the other island with its key`() {
        val roster = listOf("m1")
        val a = Islands.decide("m1", IslandState("aaaa", host = false, members = roster), listOf(host("aaaa", roster), host("bbbb")), now = 0)
        assertEquals(IslandAction.Ferry("bbbb", "DIRECT-CH-bbbb", "clave-bbbb"), a)
    }

    @Test
    fun `no other island in sight - the member stays`() {
        val roster = listOf("m1", "m2")
        assertEquals(IslandAction.Stay, Islands.decide("m1", IslandState("aaaa", false, roster), listOf(host("aaaa", roster), member("m2", "aaaa")), now = 0))
    }

    @Test
    fun `a cartel fits in a Wi-Fi Direct service record and reads back`() {
        val c = Cartel("0123456789abcdef", "Hermano", "0123456789abcdef", "DIRECT-CH-012345", "clave-xyz", host = true, roster = listOf("aaaaaaaa", "bbbbbbbb"))
        val txt = c.toTxt()
        assertEquals(true, txt.entries.sumOf { it.key.length + it.value.length + 2 } <= 255)
        assertEquals(c, Cartel.fromTxt(txt))
    }

    @Test
    fun `bridge - a member that sees people of another island but not its host founds a bridge island at the border`() {
        val roster = listOf("m1", "m2")
        val state = IslandState("aaaa", host = false, members = roster)
        val seen = listOf(host("aaaa", roster), member("x1", island = "bbbb"))
        assertEquals(IslandAction.Host, Islands.decide("m1", state, seen, now = 0))
    }

    @Test
    fun `bridge - a lonely bridge host keeps its post instead of merging back`() {
        val me = IslandState(island = "m1", host = true, members = emptyList())
        val seen = listOf(host("aaaa", listOf("m2")), member("x1", island = "bbbb"))
        assertEquals(IslandAction.Stay, Islands.decide("m1", me, seen, now = 0))
    }

    @Test
    fun `ferry - one trip per turn, not a trip every few seconds`() {
        val roster = listOf("m1")
        val seen = listOf(host("aaaa", roster), host("bbbb"))
        val turn = Islands.turnOf(0)
        assertEquals(IslandAction.Stay, Islands.decide("m1", IslandState("aaaa", false, roster, ferriedTurn = turn), seen, now = 0))
        assertTrue(Islands.decide("m1", IslandState("aaaa", false, roster, ferriedTurn = turn), seen, now = Islands.FERRY_TURN_MS) is IslandAction.Ferry)
    }

    @Test
    fun `ferry seat - an island keeps one seat free for visiting ferries`() {
        val almostFull = host("bbbb", (1 until Islands.MAX_MEMBERS).map { "m$it" })
        assertEquals(IslandAction.Host, Islands.decide("zzzz", alone, listOf(almostFull), now = 0), "a newcomer does not take the ferry seat")
        val roster = listOf("m1")
        assertTrue(Islands.decide("m1", IslandState("aaaa", false, roster), listOf(host("aaaa", roster), almostFull), now = 0) is IslandAction.Ferry,
            "a ferry can still visit")
    }

    @Test
    fun `compass ferry - the neighbor island rotates with the turn, so no direction is left without a boat`() {
        val roster = listOf("m1")
        val mine = host("aaaa", roster)
        val seen = listOf(mine, host("cccc"), host("bbbb"))
        val targets = (0L until 4L).map { t ->
            (Islands.decide("m1", IslandState("aaaa", false, roster), seen, now = t * Islands.FERRY_TURN_MS) as IslandAction.Ferry).island
        }
        assertEquals(listOf("bbbb", "cccc", "bbbb", "cccc"), targets)
    }

    @Test
    fun `compass ferry - the ferry learns the cell of the island it sails to, and the cartel carries it`() {
        val cell = Zone.of(-34.59, -58.41)
        val there = host("bbbb").copy(cell = cell)
        val a = Islands.decide("m1", IslandState("aaaa", false, listOf("m1")), listOf(host("aaaa", listOf("m1")), there), now = 0)
        assertEquals(cell, (a as IslandAction.Ferry).cell)
        val big = Cartel("0123456789abcdef", "Hermano", "0123456789abcdef", "DIRECT-CH-012345", "clave-xyz", host = true,
            roster = listOf("aaaaaaaa", "bbbbbbbb", "cccccccc", "dddddddd", "eeeeeeee", "ffffffff", "11111111"), cell = cell)
        assertTrue(big.toTxt().entries.sumOf { it.key.length + it.value.length + 2 } <= 255)
        assertEquals(big, Cartel.fromTxt(big.toTxt()))
    }

    private val here = Zone.of(-34.59, -58.43)
    private val eastward = Zone.of(-34.59, -58.37, Zone.BARRIO)

    @Test
    fun `ferry of need - whoever holds stuck letters sails toward the island that gets them closer, whatever the turn`() {
        val roster = listOf("m0", "m1") // turn 0 belongs to m0, not to me (m1)
        val west = host("bbbb").copy(cell = Zone.of(-34.59, -58.4315))
        val east = host("cccc").copy(cell = Zone.of(-34.59, -58.4285))
        val seen = listOf(host("aaaa", roster), west, east)
        val a = Islands.decide("m1", IslandState("aaaa", false, roster), seen, now = 0, myCell = here, stuck = eastward)
        assertEquals("cccc", (a as IslandAction.Ferry).island)
        assertEquals(IslandAction.Stay, Islands.decide("m1", IslandState("aaaa", false, roster, ferriedTurn = 0), seen, now = 0, myCell = here, stuck = eastward),
            "once per turn")
    }

    @Test
    fun `ferry of need - a lonely bridge host may sail too, a host with members never`() {
        val east = host("cccc").copy(cell = Zone.of(-34.59, -58.4285))
        val lonely = Islands.decide("aaaa", IslandState("aaaa", true, emptyList()), listOf(host("aaaa"), east, member("x", "dddd")), now = 0, myCell = here, stuck = eastward)
        assertEquals("cccc", (lonely as IslandAction.Ferry).island)
        val busy = Islands.decide("aaaa", IslandState("aaaa", true, listOf("m1")), listOf(host("aaaa", listOf("m1")), east), now = 0, myCell = here, stuck = eastward)
        assertEquals(IslandAction.Stay, busy)
    }

    /* ---------- el puente fijo (Camino y Carretera §6.2): one foot in each island ---------- */

    @Test
    fun `fixed bridge - a member that can hold two connections stays in both islands`() {
        val roster = listOf("m1", "m2")
        val a = Islands.decide("m1", IslandState("aaaa", false, roster, canBridge = true), listOf(host("aaaa", roster), host("bbbb")), now = 0)
        assertEquals(IslandAction.Bridge("bbbb", "DIRECT-CH-bbbb", "clave-bbbb"), a)
    }

    @Test
    fun `fixed bridge - one per pair of islands, and without the capability it is the ferry as always`() {
        val roster = listOf("m1", "m2")
        val already = member("m2", "aaaa").copy(bridgeTo = "bbbb")
        assertTrue(Islands.decide("m1", IslandState("aaaa", false, roster, canBridge = true), listOf(host("aaaa", roster), host("bbbb"), already), now = 0) !is IslandAction.Bridge)
        assertTrue(Islands.decide("m1", IslandState("aaaa", false, roster), listOf(host("aaaa", roster), host("bbbb")), now = 0) !is IslandAction.Bridge)
    }

    @Test
    fun `fixed bridge - two that started at once, the bigger id lets go`() {
        val roster = listOf("m1", "m2")
        val smaller = member("m1", "aaaa").copy(bridgeTo = "bbbb")
        assertEquals(IslandAction.Unbridge, Islands.decide("m2", IslandState("aaaa", false, roster, bridging = "bbbb", canBridge = true), listOf(host("aaaa", roster), host("bbbb"), smaller), now = 0))
        assertEquals(IslandAction.Stay, Islands.decide("m1", IslandState("aaaa", false, roster, bridging = "bbbb", canBridge = true), listOf(host("aaaa", roster), host("bbbb"), member("m2", "aaaa").copy(bridgeTo = "bbbb")), now = 0))
    }

    @Test
    fun `fixed bridge - the cartel carries it`() {
        val c = Cartel("0123456789abcdef", "Hermano", "aaaa", "", "", host = false, roster = emptyList(), bridgeTo = "bbbbbbbbbbbbbbbb")
        assertEquals(c, Cartel.fromTxt(c.toTxt()))
    }

    /* ---------- islands seen in the Wi-Fi list (Camino y Carretera §6.4) ---------- */

    @Test
    fun `an island in the Wi-Fi list is a host with the key that comes from its name`() {
        val c = Islands.fromScan("DIRECT-CH-6e6375")!!
        assertTrue(c.host)
        assertEquals("6e6375", c.island)
        assertEquals("DIRECT-CH-6e6375", c.ssid)
        assertEquals(Islands.passphraseFor("DIRECT-CH-6e6375"), c.passphrase)
        assertEquals(20, c.passphrase.length)
        assertTrue(Islands.passphraseFor("DIRECT-CH-6e6375") != Islands.passphraseFor("DIRECT-CH-abcdef"))
        assertEquals(null, Islands.fromScan("DIRECT-xy-Android_1234"))
        assertEquals(null, Islands.fromScan("DIRECT-CH-zzzzzz"))
        assertEquals(null, Islands.fromScan("Fibertel WiFi 5G"))
    }

    @Test
    fun `two lonely hosts that only see each other in the Wi-Fi list still merge - the bigger id joins`() {
        val big = "ffff000011112222"; val small = "1111000011112222"
        val a = Islands.decide(big, IslandState(Islands.islandId(big), true, emptyList()), listOf(Islands.fromScan("DIRECT-CH-" + small.take(6))!!), now = 0)
        assertEquals(IslandAction.Join("111100", "DIRECT-CH-111100", Islands.passphraseFor("DIRECT-CH-111100")), a)
        val b = Islands.decide(small, IslandState(Islands.islandId(small), true, emptyList()), listOf(Islands.fromScan("DIRECT-CH-" + big.take(6))!!), now = 0)
        assertEquals(IslandAction.Stay, b)
    }

    /* ---------- one sees, the other does not (field test 0.8.1: the bear hug) ---------- */

    private val capitan = "6e63751b4968bab0"
    private val mito = "48ab2393aaaabbbb"
    private fun lonely(id: String, seen: Map<String, Long> = emptyMap()) = IslandState(Islands.islandId(id), true, emptyList(), seenSince = seen)
    private fun hostSeeing(id: String, sees: List<String>?) = Cartel(Islands.islandId(id), "H", Islands.islandId(id), Islands.ssidOf(id),
        Islands.passphraseFor(Islands.ssidOf(id)), host = true, roster = emptyList(), sees = sees)

    @Test
    fun `a host's cartel says which islands it sees, and an old cartel says nothing about it`() {
        val c = hostSeeing(capitan, listOf("48ab23", "abcdef"))
        assertEquals(listOf("48ab23", "abcdef"), Cartel.fromTxt(c.toTxt())!!.sees)
        assertEquals(emptyList<String>(), Cartel.fromTxt(hostSeeing(capitan, emptyList()).toTxt())!!.sees, "sees nobody")
        assertEquals(null, Cartel.fromTxt(host("bbbb").toTxt())!!.sees, "unknown")
    }

    @Test
    fun `the one who sees moves - a lonely host joins a lonely island that cannot see it, even with a smaller id`() {
        val capitanCantSee = hostSeeing(capitan, emptyList())
        val early = Islands.decide(mito, lonely(mito, mapOf("6e6375" to 0L)), listOf(capitanCantSee), now = Islands.ASYM_WAIT_MS - 1)
        assertEquals(IslandAction.Stay, early, "first give its cartel time to catch up")
        val later = Islands.decide(mito, lonely(mito, mapOf("6e6375" to 0L)), listOf(capitanCantSee), now = Islands.ASYM_WAIT_MS)
        assertEquals(IslandAction.Join("6e6375", "DIRECT-CH-6e6375", Islands.passphraseFor("DIRECT-CH-6e6375")), later)
    }

    @Test
    fun `when both see each other only the bigger id moves, however long it takes`() {
        val long = Islands.SCAN_WAIT_MS * 10
        val fromMito = Islands.decide(mito, lonely(mito, mapOf("6e6375" to 0L)), listOf(hostSeeing(capitan, listOf("48ab23"))), now = long)
        assertEquals(IslandAction.Stay, fromMito)
        val fromCapitan = Islands.decide(capitan, lonely(capitan, mapOf("48ab23" to 0L)), listOf(hostSeeing(mito, listOf("6e6375"))), now = long)
        assertEquals("48ab23", (fromCapitan as IslandAction.Join).island)
    }

    @Test
    fun `an island seen only in the Wi-Fi list that never comes - after a while I go`() {
        val listed = Islands.fromScan("DIRECT-CH-6e6375")!!
        assertEquals(IslandAction.Stay, Islands.decide(mito, lonely(mito, mapOf("6e6375" to 0L)), listOf(listed), now = Islands.SCAN_WAIT_MS - 1))
        assertEquals("6e6375", (Islands.decide(mito, lonely(mito, mapOf("6e6375" to 0L)), listOf(listed), now = Islands.SCAN_WAIT_MS) as IslandAction.Join).island)
    }

    /* ---------- the phone on a home Wi-Fi does not hold the island (field test 0.9.3, Camino y Carretera §6.12) ---------- */

    private val known = listOf("48ab23", "abcdef")
    private fun onWifi(island: String? = null, host: Boolean = false, members: List<String> = emptyList(), looking: Long = 0, hostSince: Long = 0) =
        IslandState(island, host, members, onWifiNetwork = true, known = known, lookingSince = looking, hostSince = hostSince)

    @Test
    fun `a phone on a home Wi-Fi joins a contact's island by its name instead of founding one, even without seeing it`() {
        val a = Islands.decide(capitan, onWifi(), emptyList(), now = 0)
        assertEquals(IslandAction.Join("48ab23", "DIRECT-CH-48ab23", Islands.passphraseFor("DIRECT-CH-48ab23")), a)
        val next = Islands.decide(capitan, onWifi(), emptyList(), now = Islands.BLIND_TRY_MS)
        assertEquals("abcdef", (next as IslandAction.Join).island, "and tries the next contact's island after a while")
    }

    @Test
    fun `an island it can see comes before a blind try`() {
        val a = Islands.decide(capitan, onWifi(), listOf(host("bbbbbb", listOf("m1"))), now = 0)
        assertEquals("bbbbbb", (a as IslandAction.Join).island)
    }

    @Test
    fun `if no contact's island answers, the phone on a home Wi-Fi founds its own after a while`() {
        assertEquals(IslandAction.Host, Islands.decide(capitan, onWifi(looking = 0), emptyList(), now = Islands.BLIND_GIVEUP_MS))
    }

    @Test
    fun `a free phone founds as always, and without contacts so does the one on a home Wi-Fi`() {
        assertEquals(IslandAction.Host, Islands.decide(mito, IslandState(null, false, emptyList()), emptyList(), now = 0))
        assertEquals(IslandAction.Host, Islands.decide(capitan, IslandState(null, false, emptyList(), onWifiNetwork = true), emptyList(), now = 0))
    }

    @Test
    fun `a lonely host on a home Wi-Fi tries again to leave the island to a free phone - one with members never`() {
        val lonely = onWifi(island = "6e6375", host = true, hostSince = 0)
        assertEquals(IslandAction.Stay, Islands.decide(capitan, lonely, emptyList(), now = Islands.BLIND_RETRY_MS - 1))
        assertEquals("48ab23", (Islands.decide(capitan, lonely, emptyList(), now = Islands.BLIND_RETRY_MS) as IslandAction.Join).island)
        val withMembers = onWifi(island = "6e6375", host = true, members = listOf("m1"), hostSince = 0)
        assertEquals(IslandAction.Stay, Islands.decide(capitan, withMembers, emptyList(), now = Islands.BLIND_RETRY_MS * 5))
    }

    /* ---------- the cartel says "I am on a home Wi-Fi": the free phone holds the island (field test 0.9.4) ---------- */

    private fun lonelyHost(id: String, onWifi: Boolean, members: List<String> = emptyList()) =
        Cartel(Islands.islandId(id), "H", Islands.islandId(id), Islands.ssidOf(id), Islands.passphraseFor(Islands.ssidOf(id)), host = true, roster = members, onWifi = onWifi)

    @Test
    fun `the cartel says whether its phone is on a home Wi-Fi`() {
        assertTrue(Cartel.fromTxt(lonelyHost(capitan, onWifi = true).toTxt())!!.onWifi)
        assertTrue(!Cartel.fromTxt(lonelyHost(mito, onWifi = false).toTxt())!!.onWifi)
    }

    @Test
    fun `a free phone does not join a lonely island held by a phone on a home Wi-Fi - it founds the steady one`() {
        assertEquals(IslandAction.Host, Islands.decide(mito, IslandState(null, false, emptyList()), listOf(lonelyHost(capitan, onWifi = true)), now = 0))
        val withMembers = lonelyHost(capitan, onWifi = true, members = listOf("m1"))
        assertEquals("6e6375", (Islands.decide(mito, IslandState(null, false, emptyList()), listOf(withMembers), now = 0) as IslandAction.Join).island,
            "but it does not split an island that already has people")
    }

    @Test
    fun `a phone on a home Wi-Fi goes to the free phone's island before any other`() {
        val a = Islands.decide(capitan, onWifi(), listOf(lonelyHost("aaaaaa0000", onWifi = true, members = listOf("x")), lonelyHost(mito, onWifi = false)), now = 0)
        assertEquals("48ab23", (a as IslandAction.Join).island)
    }

    @Test
    fun `two lonely hosts - the one on a home Wi-Fi moves to the free one, whatever the ids`() {
        val capitanAlone = onWifi(island = "6e6375", host = true, hostSince = 0)
        assertEquals("48ab23", (Islands.decide(capitan, capitanAlone, listOf(lonelyHost(mito, onWifi = false)), now = 0) as IslandAction.Join).island)
        val mitoAlone = IslandState("48ab23", true, emptyList())
        val bigFree = "ffffff0000000000"
        assertEquals(IslandAction.Stay, Islands.decide(bigFree, IslandState("ffffff", true, emptyList()), listOf(lonelyHost(capitan, onWifi = true)), now = 10 * Islands.SCAN_WAIT_MS),
            "a free host never moves to a lonely island on a home Wi-Fi, not even by the id rule")
        assertEquals(IslandAction.Stay, Islands.decide(mito, mitoAlone, listOf(lonelyHost(capitan, onWifi = true)), now = 0))
    }
}
