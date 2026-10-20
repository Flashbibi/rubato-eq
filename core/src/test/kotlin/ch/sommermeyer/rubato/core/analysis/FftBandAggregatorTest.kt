package ch.sommermeyer.rubato.core.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FftBandAggregatorTest {
    private val aggregator = FftBandAggregator(BandLayout.DEFAULT)

    @Test
    fun `bin ranges at 48 kHz follow the DynamicsProcessing rounding`() {
        val ranges = aggregator.binRanges(captureSize = 1024, samplingRateHz = 48_000.0)
        assertEquals(
            listOf(1..4, 5..8, 9..15, 16..30, 31..60, 61..121, 122..241, 242..341),
            ranges,
        )
    }

    @Test
    fun `bin ranges at 44_1 kHz`() {
        val ranges = aggregator.binRanges(captureSize = 1024, samplingRateHz = 44_100.0)
        assertEquals(
            listOf(1..4, 5..8, 9..16, 17..33, 34..66, 67..131, 132..263, 264..371),
            ranges,
        )
    }

    @Test
    fun `lowest band is wide enough to contain several bins`() {
        val lowest = aggregator.binRanges(1024, 48_000.0).first()
        assertEquals(4, lowest.count())
    }

    @ParameterizedTest
    @ValueSource(doubles = [44_100.0, 48_000.0, 96_000.0])
    fun `every band receives bins at common output rates`(rate: Double) {
        aggregator.binRanges(1024, rate).forEach { range -> assertFalse(range.isEmpty(), "rate $rate") }
    }

    @Test
    fun `power of bins is summed into their band`() {
        val fft = ByteArray(1024)
        fft.setBin(3, re = 10, im = 0) // 140.6 Hz -> band 0
        fft.setBin(4, re = 0, im = 2) // 187.5 Hz -> still band 0 (rounded cut-off bin)
        fft.setBin(100, re = 3, im = 4) // 4.7 kHz -> band 5

        val power = aggregator.aggregate(fft, 48_000.0)

        assertEquals(104.0, power[0], 1e-9)
        assertEquals(25.0, power[5], 1e-9)
        assertEquals(129.0, power.sum(), 1e-9)
    }

    @Test
    fun `DC, Nyquist and content above 16 kHz are ignored`() {
        val fft = ByteArray(1024)
        fft[0] = 100 // DC
        fft[1] = 100 // Nyquist
        fft.setBin(400, re = 50, im = 50) // 18.75 kHz

        val power = aggregator.aggregate(fft, 48_000.0)

        assertEquals(0.0, power.sum(), 0.0)
    }

    @Test
    fun `mapping follows a change of the sampling rate`() {
        val fft = ByteArray(1024)
        fft.setBin(16, re = 10, im = 0)
        // Bin 16 is 750 Hz at 48 kHz (band 3) but 689 Hz at 44.1 kHz (band 2).
        assertEquals(100.0, aggregator.aggregate(fft, 48_000.0)[3], 1e-9)
        assertEquals(100.0, aggregator.aggregate(fft, 44_100.0)[2], 1e-9)
    }

    private fun ByteArray.setBin(k: Int, re: Int, im: Int) {
        this[2 * k] = re.toByte()
        this[2 * k + 1] = im.toByte()
    }
}
