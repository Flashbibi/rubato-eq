package ch.sommermeyer.rubato.core.analysis

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.log10

class SpectrumAccumulatorTest {

    @Test
    fun `powers are averaged linearly before the dB conversion`() {
        val acc = SpectrumAccumulator(2)
        acc.add(doubleArrayOf(1.0, 1.0))
        acc.add(doubleArrayOf(100.0, 1.0))

        assertArrayEquals(doubleArrayOf(50.5, 1.0), acc.meanPower(), 1e-12)
        // Linear mean: 10*log10(50.5) = 17.03 dB above band 1. Averaging in dB would give 10 dB.
        val spread = 10 * log10(50.5)
        assertArrayEquals(doubleArrayOf(spread / 2, -spread / 2), acc.spectrumDb(), 1e-9)
    }

    @Test
    fun `normalised spectrum does not depend on the level`() {
        val quiet = SpectrumAccumulator(3).apply { add(doubleArrayOf(1.0, 4.0, 0.5)) }
        val loud = SpectrumAccumulator(3).apply { add(doubleArrayOf(1000.0, 4000.0, 500.0)) }

        assertArrayEquals(quiet.spectrumDb(), loud.spectrumDb(), 1e-9)
        assertEquals(0.0, quiet.spectrumDb()!!.average(), 1e-9)
    }

    @Test
    fun `applied EQ gain is removed from the measurement`() {
        val acc = SpectrumAccumulator(2)
        // Band 0 was boosted by +6.02 dB (power x4) by our own EQ.
        acc.add(doubleArrayOf(4.0, 1.0), appliedGainDb = doubleArrayOf(10 * log10(4.0), 0.0))

        assertArrayEquals(doubleArrayOf(1.0, 1.0), acc.meanPower(), 1e-9)
        assertArrayEquals(doubleArrayOf(0.0, 0.0), acc.spectrumDb(), 1e-9)
    }

    @Test
    fun `merged history counts with its frame count`() {
        val acc = SpectrumAccumulator(2)
        acc.merge(doubleArrayOf(2.0, 2.0), frameCount = 3)
        acc.add(doubleArrayOf(6.0, 6.0))

        assertEquals(4, acc.frames)
        assertArrayEquals(doubleArrayOf(3.0, 3.0), acc.meanPower(), 1e-12)
    }

    @Test
    fun `empty accumulator has no spectrum`() {
        val acc = SpectrumAccumulator(4)
        assertNull(acc.meanPower())
        assertNull(acc.spectrumDb())
        acc.add(doubleArrayOf(1.0, 1.0, 1.0, 1.0))
        acc.reset()
        assertNull(acc.spectrumDb())
    }
}
