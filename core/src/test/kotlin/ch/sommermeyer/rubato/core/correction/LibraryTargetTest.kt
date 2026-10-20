package ch.sommermeyer.rubato.core.correction

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LibraryTargetTest {
    private val songs = listOf(
        doubleArrayOf(3.0, 0.0, -3.0),
        doubleArrayOf(1.0, 0.0, -1.0),
        doubleArrayOf(-1.0, 2.0, -1.0),
    )

    @Test
    fun `running average equals the average over all songs`() {
        val library = LibraryTarget(3)
        songs.forEach(library::add)

        assertEquals(3, library.songCount)
        assertArrayEquals(doubleArrayOf(1.0, 2.0 / 3, -5.0 / 3), library.mean(), 1e-12)
        assertArrayEquals(library.mean(), LibraryTarget.of(3, songs).mean(), 1e-12)
    }

    @Test
    fun `re-analysed song replaces its contribution instead of counting twice`() {
        val library = LibraryTarget.of(3, songs)
        val updated = doubleArrayOf(5.0, 0.0, -5.0)

        library.replace(previousDb = songs[0], updatedDb = updated)

        assertEquals(3, library.songCount)
        assertArrayEquals(LibraryTarget.of(3, listOf(updated, songs[1], songs[2])).mean(), library.mean(), 1e-12)
    }

    @Test
    fun `empty library has no target`() {
        val library = LibraryTarget(3)
        assertNull(library.mean())
        assertEquals(0.0, library.confidence())
        library.add(songs[0])
        library.clear()
        assertNull(library.mean())
    }

    @Test
    fun `confidence grows with n over 20 and saturates`() {
        assertEquals(0.0, LibraryTarget.confidence(0))
        assertEquals(0.05, LibraryTarget.confidence(1), 1e-12)
        assertEquals(0.5, LibraryTarget.confidence(10), 1e-12)
        assertEquals(1.0, LibraryTarget.confidence(20))
        assertEquals(1.0, LibraryTarget.confidence(250))
    }

    @Test
    fun `correction parameters combine library, taste offset and scaled strength`() {
        val library = LibraryTarget.of(8, List(5) { DoubleArray(8) { b -> b.toDouble() } })
        val params = CorrectionParams.from(
            library = library,
            strength = 0.8,
            maxDb = 3.0,
            taste = ToneOffset(bassDb = 2.0),
        )

        val target = params.target!!
        assertEquals(0.8 * 5 / 20, params.effectiveStrength, 1e-12)
        assertEquals(0.0 + 2.0, target[0], 1e-12) // band 0 has full bass weight
        assertEquals(7.0, target[7], 1e-12) // no bass weight in the top band
        assertNull(CorrectionParams.from(LibraryTarget(8), 1.0, 3.0, ToneOffset.NEUTRAL).target)
    }
}
