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
            https://rele.chamullo.ar
        """.trimIndent()
        assertEquals(listOf("https://rele.chamullo.ar", "http://200.1.2.3:47475"), Relay.parseList(text))
        assertEquals(emptyList<String>(), Relay.parseList(""))
    }
}
