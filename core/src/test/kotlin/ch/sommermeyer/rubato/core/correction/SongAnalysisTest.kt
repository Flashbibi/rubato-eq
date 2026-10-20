package ch.sommermeyer.rubato.core.correction

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class SongAnalysisTest {
    private val flat = DoubleArray(8) { 1.0 }
    private val bright = DoubleArray(8) { band -> if (band >= 6) 100.0 else 1.0 }
    private val target = doubleArrayOf(3.0, 1.0, 0.0, 0.0, 0.0, 0.0, -1.0, -3.0)
    private val params = CorrectionParams(target, effectiveStrength = 1.0, maxDb = 6.0)

    @Test
    fun `first correction after 5 s of analysed audio, applied in full`() {
        val analysis = SongAnalysis()
        analysis.feed(4_950)
        assertNull(analysis.evaluate(params))

        analysis.feed(50)
        val first = analysis.evaluate(params)

        assertNotNull(first)
        assertArrayEquals(CorrectionMath.autoCorrection(target, DoubleArray(8), 1.0, 6.0), first, 1e-9)
        assertNull(analysis.evaluate(params), "no second correction right away")
    }

    @Test
    fun `frames right after the song change are discarded`() {
        val analysis = SongAnalysis()
        assertFalse(analysis.addFrame(flat, frameDurationMs = 50, msSinceStart = 400))
        assertEquals(0, analysis.totalAnalyzedMs)
        assertTrue(analysis.addFrame(flat, frameDurationMs = 50, msSinceStart = 1_000))
        assertEquals(50, analysis.totalAnalyzedMs)
    }

    @Test
    fun `follow-up corrections every 10 s move at most 0_5 dB per band`() {
        val analysis = SongAnalysis()
        analysis.feed(5_000)
        val first = analysis.evaluate(params)!!

        // The song turns much brighter, so the desired correction changes a lot.
        analysis.feed(9_950, power = bright)
        assertNull(analysis.evaluate(params))
        analysis.feed(50, power = bright)
        val second = analysis.evaluate(params)!!

        val steps = second.indices.map { abs(second[it] - first[it]) }
        assertTrue(steps.all { it <= 0.5 + 1e-9 }, "steps: $steps")
        assertEquals(0.5, steps.max(), 1e-9)

        analysis.feed(9_950, power = bright)
        assertNull(analysis.evaluate(params))
        analysis.feed(50, power = bright)
        assertNotNull(analysis.evaluate(params))
    }

    @Test
    fun `forced evaluation applies the new settings immediately and without step limit`() {
        val analysis = SongAnalysis()
        analysis.feed(5_000)
        analysis.evaluate(params)
        val stronger = CorrectionParams(doubleArrayOf(6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0), 1.0, 6.0)

        val forced = analysis.evaluate(stronger, force = true)

        assertArrayEquals(DoubleArray(8) { 6.0 }, forced, 1e-9)
    }

    @Test
    fun `forced evaluation waits for enough data`() {
        val analysis = SongAnalysis()
        analysis.feed(2_000)
        assertNull(analysis.evaluate(params, force = true))
    }

    @Test
    fun `known song starts with its stored correction and refines it slowly`() {
        val stored = DoubleArray(8) { 2.0 }
        val analysis = SongAnalysis()
        analysis.restore(StoredAnalysis(meanPower = flat, frameCount = 1_200, analyzedMs = 60_000, autoCorrectionDb = stored))

        assertArrayEquals(stored, analysis.currentCorrection, 1e-12)
        assertTrue(analysis.hasEnoughData)
        assertTrue(analysis.isLibraryReady)
        assertNull(analysis.evaluate(params), "stored correction stays until the first regular update")

        analysis.feed(5_000)
        val updated = analysis.evaluate(params)!!
        updated.indices.forEach { assertTrue(abs(updated[it] - stored[it]) <= 0.5 + 1e-9) }
    }

    @Test
    fun `without library there is no automatic correction`() {
        val analysis = SongAnalysis()
        analysis.feed(5_000)
        val correction = analysis.evaluate(CorrectionParams(target = null, effectiveStrength = 1.0, maxDb = 3.0))
        assertArrayEquals(DoubleArray(8), correction, 0.0)
    }

    @Test
    fun `measurement with our own EQ equals the dry measurement after compensation`() {
        val gains = doubleArrayOf(3.0, 1.0, 0.0, 0.0, 0.0, 0.0, -1.0, -3.0)
        val wet = DoubleArray(8) { bright[it] * Math.pow(10.0, gains[it] / 10) }
        val dry = SongAnalysis().apply { feed(5_000, power = bright) }
        val compensated = SongAnalysis().apply { feed(5_000, power = wet, applied = gains) }

        assertArrayEquals(dry.spectrumDb(), compensated.spectrumDb(), 1e-9)
    }

    @Test
    fun `song joins the library after 20 s of analysed audio`() {
        val analysis = SongAnalysis()
        analysis.feed(19_950)
        assertFalse(analysis.isLibraryReady)
        analysis.feed(50)
        assertTrue(analysis.isLibraryReady)
    }

    private fun SongAnalysis.feed(
        durationMs: Long,
        power: DoubleArray = flat,
        applied: DoubleArray? = null,
        frameMs: Long = 50,
    ) {
        val start = 2_000L + totalAnalyzedMs
        var t = 0L
        while (t < durationMs) {
            addFrame(power, frameMs, msSinceStart = start + t, appliedGainDb = applied)
            t += frameMs
        }
    }
}
