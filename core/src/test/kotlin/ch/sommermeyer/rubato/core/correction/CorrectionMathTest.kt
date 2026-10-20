package ch.sommermeyer.rubato.core.correction

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CorrectionMathTest {

    @Test
    fun `smoothing spreads an impulse to the neighbours`() {
        val smoothed = CorrectionMath.smooth(doubleArrayOf(0.0, 0.0, 4.0, 0.0, 0.0))
        assertArrayEquals(doubleArrayOf(0.0, 1.0, 2.0, 1.0, 0.0), smoothed, 1e-12)
    }

    @Test
    fun `smoothing keeps constant curves and renormalises at the edges`() {
        assertArrayEquals(doubleArrayOf(2.0, 2.0, 2.0), CorrectionMath.smooth(doubleArrayOf(2.0, 2.0, 2.0)), 1e-12)
        assertArrayEquals(doubleArrayOf(2.0, 0.75, 0.0), CorrectionMath.smooth(doubleArrayOf(3.0, 0.0, 0.0)), 1e-12)
    }

    @Test
    fun `correction scales with strength`() {
        val target = doubleArrayOf(2.0, 2.0, 2.0)
        val measured = doubleArrayOf(0.0, 0.0, 0.0)

        val full = CorrectionMath.autoCorrection(target, measured, strength = 1.0, maxDb = 6.0)
        val half = CorrectionMath.autoCorrection(target, measured, strength = 0.5, maxDb = 6.0)
        val off = CorrectionMath.autoCorrection(target, measured, strength = 0.0, maxDb = 6.0)

        assertArrayEquals(doubleArrayOf(2.0, 2.0, 2.0), full, 1e-12)
        assertArrayEquals(doubleArrayOf(1.0, 1.0, 1.0), half, 1e-12)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), off, 1e-12)
    }

    @Test
    fun `correction is limited to the configured maximum`() {
        val boost = CorrectionMath.autoCorrection(doubleArrayOf(10.0, 10.0, 10.0), DoubleArray(3), 1.0, maxDb = 3.0)
        val cut = CorrectionMath.autoCorrection(DoubleArray(2), doubleArrayOf(10.0, 10.0), 1.0, maxDb = 3.0)
        val wide = CorrectionMath.autoCorrection(doubleArrayOf(10.0, 10.0), DoubleArray(2), 1.0, maxDb = 6.0)

        assertArrayEquals(doubleArrayOf(3.0, 3.0, 3.0), boost, 1e-12)
        assertArrayEquals(doubleArrayOf(-3.0, -3.0), cut, 1e-12)
        assertArrayEquals(doubleArrayOf(6.0, 6.0), wide, 1e-12)
    }

    @Test
    fun `smoothing removes alternating band-to-band patterns`() {
        val target = doubleArrayOf(10.0, -10.0, 10.0, -10.0)

        val correction = CorrectionMath.autoCorrection(target, DoubleArray(4), strength = 1.0, maxDb = 6.0)

        assertArrayEquals(doubleArrayOf(10.0 / 3, 0.0, 0.0, -10.0 / 3), correction, 1e-12)
    }

    @Test
    fun `hard clamp keeps values inside the limit`() {
        val clamped = CorrectionMath.clamp(doubleArrayOf(-20.0, -1.0, 0.0, 13.0), CorrectionMath.HARD_LIMIT_DB)
        assertArrayEquals(doubleArrayOf(-12.0, -1.0, 0.0, 12.0), clamped, 1e-12)
        assertEquals(12.0, CorrectionMath.HARD_LIMIT_DB)
    }

    @Test
    fun `correction is target minus measurement, smoothed before limiting`() {
        val target = doubleArrayOf(0.0, 4.0, 0.0)
        val measured = doubleArrayOf(0.0, 0.0, 0.0)

        val correction = CorrectionMath.autoCorrection(target, measured, strength = 1.0, maxDb = 6.0)

        assertArrayEquals(doubleArrayOf(4.0 / 3, 2.0, 4.0 / 3), correction, 1e-12)
        // A bass-heavy song (measured above target) is cut, a thin one boosted.
        val cut = CorrectionMath.autoCorrection(doubleArrayOf(0.0, 0.0), doubleArrayOf(2.0, 2.0), 1.0, 6.0)
        assertArrayEquals(doubleArrayOf(-2.0, -2.0), cut, 1e-12)
    }

    @Test
    fun `step limit moves at most the given amount per band`() {
        val next = CorrectionMath.limitStep(
            current = doubleArrayOf(0.0, 0.0, 1.0),
            desired = doubleArrayOf(2.0, -0.2, -3.0),
            maxStepDb = 0.5,
        )
        assertArrayEquals(doubleArrayOf(0.5, -0.2, 0.5), next, 1e-12)
    }
}
