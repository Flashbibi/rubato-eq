package ch.sommermeyer.rubato.core.correction

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GainRampTest {

    @Test
    fun `ramp reaches the target linearly over its duration`() {
        val ramp = GainRamp(bandCount = 2, durationMs = 1_500)
        ramp.retarget(doubleArrayOf(3.0, -1.5), nowMs = 1_000)

        assertArrayEquals(doubleArrayOf(0.0, 0.0), ramp.valueAt(1_000), 1e-12)
        assertArrayEquals(doubleArrayOf(1.5, -0.75), ramp.valueAt(1_750), 1e-12)
        assertFalse(ramp.isSettled(2_499))
        assertArrayEquals(doubleArrayOf(3.0, -1.5), ramp.valueAt(2_500), 1e-12)
        assertTrue(ramp.isSettled(2_500))
        assertArrayEquals(doubleArrayOf(3.0, -1.5), ramp.valueAt(10_000), 1e-12)
    }

    @Test
    fun `new target mid-ramp continues from the current value without a jump`() {
        val ramp = GainRamp(bandCount = 1, durationMs = 1_500)
        ramp.retarget(doubleArrayOf(3.0), nowMs = 0)
        val before = ramp.valueAt(750)

        ramp.retarget(doubleArrayOf(0.0), nowMs = 750)

        assertArrayEquals(before, ramp.valueAt(750), 1e-12)
        assertArrayEquals(doubleArrayOf(0.75), ramp.valueAt(1_500), 1e-12)
        assertArrayEquals(doubleArrayOf(0.0), ramp.valueAt(2_250), 1e-12)
    }

    @Test
    fun `repeating the same target does not restart the ramp`() {
        val ramp = GainRamp(bandCount = 1, durationMs = 1_500)
        ramp.retarget(doubleArrayOf(3.0), nowMs = 0)
        ramp.retarget(doubleArrayOf(3.0), nowMs = 1_000)

        assertTrue(ramp.isSettled(1_500))
    }

    @Test
    fun `steps of a ramp stay small`() {
        val ramp = GainRamp(bandCount = 1, durationMs = 1_500)
        ramp.retarget(doubleArrayOf(6.0), nowMs = 0)
        var previous = 0.0
        for (t in 0L..1_500L step 40) {
            val value = ramp.valueAt(t)[0]
            assertTrue(value - previous <= 6.0 * 40 / 1_500 + 1e-9)
            previous = value
        }
    }

    @Test
    fun `reset sets the value immediately`() {
        val ramp = GainRamp(bandCount = 1)
        ramp.retarget(doubleArrayOf(3.0), nowMs = 0)
        ramp.reset(doubleArrayOf(0.0), nowMs = 100)

        assertArrayEquals(doubleArrayOf(0.0), ramp.valueAt(100), 1e-12)
        assertTrue(ramp.isSettled(100))
    }
}
