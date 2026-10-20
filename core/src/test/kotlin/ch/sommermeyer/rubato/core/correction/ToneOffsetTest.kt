package ch.sommermeyer.rubato.core.correction

import ch.sommermeyer.rubato.core.analysis.BandLayout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToneOffsetTest {

    @Test
    fun `bass acts on the low bands, treble on the high bands`() {
        val bass = ToneOffset(bassDb = 2.0).toBands()
        val treble = ToneOffset(trebleDb = -3.0).toBands()

        assertArrayEquals(doubleArrayOf(2.0, 1.2, 0.4, 0.0, 0.0, 0.0, 0.0, 0.0), bass, 1e-12)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0, 0.0, -0.6, -1.8, -3.0, -3.0), treble, 1e-12)
    }

    @Test
    fun `mid control peaks around 1 kHz`() {
        val mid = ToneOffset(midDb = 1.0).toBands()
        val peak = mid.indices.maxBy { mid[it] }
        assertEquals("1k", BandLayout.DEFAULT[peak].label)
        assertEquals(0.0, mid.first())
        assertEquals(0.0, mid.last())
    }

    @Test
    fun `controls add up`() {
        val combined = ToneOffset(1.0, 2.0, 3.0).toBands()
        val separate = CorrectionMath.add(
            CorrectionMath.add(ToneOffset(bassDb = 1.0).toBands(), ToneOffset(midDb = 2.0).toBands()),
            ToneOffset(trebleDb = 3.0).toBands(),
        )
        assertArrayEquals(separate, combined, 1e-12)
    }

    @Test
    fun `summary of uniform gains is uniform`() {
        val summary = ToneOffset.summarize(DoubleArray(BandLayout.SIZE) { 1.5 })
        assertEquals(1.5, summary.bassDb, 1e-12)
        assertEquals(1.5, summary.midDb, 1e-12)
        assertEquals(1.5, summary.trebleDb, 1e-12)
        assertTrue(ToneOffset.NEUTRAL.isNeutral)
    }
}
