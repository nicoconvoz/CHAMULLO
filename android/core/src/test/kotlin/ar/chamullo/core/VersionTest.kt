package ar.chamullo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionTest {
    @Test
    fun `version txt is read only when it holds a plain x-y-z version`() {
        assertEquals("0.1.1", Version.parse(" 0.1.1\n"))
        assertNull(Version.parse("<html>404</html>"))
        assertNull(Version.parse("0.1"))
    }

    @Test
    fun `newer compares number by number, not as text`() {
        assertTrue(Version.newer("0.1.10", "0.1.9"))
        assertTrue(Version.newer("1.0.0", "0.9.9"))
        assertFalse(Version.newer("0.1.1", "0.1.1"))
        assertFalse(Version.newer("0.1.0", "0.1.1"))
    }

    @Test
    fun `no notice without a version from the page`() {
        assertFalse(Version.needsUpdate(web = null, installed = "0.1.0"))
        assertTrue(Version.needsUpdate(web = "0.1.1", installed = "0.1.0"))
    }
}
