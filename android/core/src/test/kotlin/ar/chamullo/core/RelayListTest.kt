// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RelayListTest {
    @Test
    fun `the published list of relays keeps only addresses, in order, without comments or junk`() {
        val text = """
            # relés de CHAMULLO: uno por línea
            https://rele.chamullo.ar
            http://200.1.2.3:47475/

            esto no es una dirección
            ftp://otra.cosa
            wss://nostr.ejemplo.ar
            https://rele.chamullo.ar
        """.trimIndent()
        assertEquals(listOf("https://rele.chamullo.ar", "http://200.1.2.3:47475", "wss://nostr.ejemplo.ar"), Relay.parseList(text))
        assertEquals(emptyList<String>(), Relay.parseList(""))
    }
}
