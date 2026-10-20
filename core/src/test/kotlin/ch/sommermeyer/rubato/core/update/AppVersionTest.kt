package ch.sommermeyer.rubato.core.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppVersionTest {

    @Test
    fun `parses release tags and version names`() {
        assertEquals(listOf(0, 1, 42), AppVersion.parse("v0.1.42")!!.parts)
        assertEquals(listOf(0, 1, 42), AppVersion.parse("0.1.42")!!.parts)
        assertEquals(listOf(0, 1, 7), AppVersion.parse("0.1.7-debug")!!.parts)
        assertEquals(listOf(0, 1, 0), AppVersion.parse(" 0.1.0-local ")!!.parts)
    }

    @Test
    fun `rejects garbage`() {
        assertNull(AppVersion.parse(null))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse("v0.x.1"))
    }

    @Test
    fun `compares numerically, not lexically`() {
        assertTrue(AppVersion.parse("0.1.10")!! > AppVersion.parse("0.1.9")!!)
        assertTrue(AppVersion.parse("0.2.0")!! > AppVersion.parse("0.1.99")!!)
        assertEquals(AppVersion.parse("1.0"), AppVersion.parse("1.0.0"))
    }

    @Test
    fun `update is offered only for newer releases`() {
        assertTrue(AppVersion.isNewer("v0.1.13", "0.1.12"))
        assertTrue(AppVersion.isNewer("v0.1.100", "0.1.99"))
        assertFalse(AppVersion.isNewer("v0.1.12", "0.1.12"))
        assertFalse(AppVersion.isNewer("v0.1.2", "0.1.10"))
        assertTrue(AppVersion.isNewer("v0.1.1", "0.1.0-local"), "local builds accept any release")
        assertFalse(AppVersion.isNewer("not-a-version", "0.1.1"))
    }
}
