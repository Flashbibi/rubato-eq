package ch.sommermeyer.rubato.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SongKeyTest {

    @Test
    fun `same song with different spelling of whitespace and case gives the same key`() {
        assertEquals(
            SongKey.of("Daft Punk", "Get Lucky", 248_000),
            SongKey.of(" daft  punk", "GET LUCKY ", 248_413),
        )
    }

    @Test
    fun `duration distinguishes versions of a song`() {
        assertNotEquals(SongKey.of("A", "Song", 200_000), SongKey.of("A", "Song", 260_000))
    }

    @Test
    fun `title is required, artist and duration are optional`() {
        assertNull(SongKey.of("Artist", null, 1_000))
        assertNull(SongKey.of("Artist", "  ", 1_000))
        assertEquals("|radio|0", SongKey.of(null, "Radio", -1)!!.value)
    }
}
