package ch.sommermeyer.rubato.core.analysis

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SilenceGateTest {
    private val signal = doubleArrayOf(400.0, 250.0, 90.0, 20.0)

    @Test
    fun `all-zero capture is silence`() {
        assertTrue(SilenceGate.isSilent(DoubleArray(4), rmsDbfs = null))
    }

    @Test
    fun `low input RMS is silence even if the normalised FFT looks loud`() {
        assertTrue(SilenceGate.isSilent(signal, rmsDbfs = -85.0))
    }

    @Test
    fun `music is not silence`() {
        assertFalse(SilenceGate.isSilent(signal, rmsDbfs = -18.0))
        assertFalse(SilenceGate.isSilent(signal, rmsDbfs = null))
    }
}
