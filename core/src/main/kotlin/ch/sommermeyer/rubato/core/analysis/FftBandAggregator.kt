package ch.sommermeyer.rubato.core.analysis

import kotlin.math.floor
import kotlin.math.min

/**
 * Sums the power of Visualizer FFT bins into bands.
 *
 * Layout of an FFT capture of size n (see `Visualizer.getFft`):
 * `[Rf0, Rf(n/2), Rf1, If1, Rf2, If2, ..., Rf(n/2-1), If(n/2-1)]`, bin k has frequency k * fs / n.
 *
 * Bins are assigned to bands with the rounding DynamicsProcessing uses for its FFT-domain EQ
 * (last bin of a band = round(cutoff * n / fs)), so measurement and correction cover the same bins
 * whenever both run on the same FFT grid. DC and Nyquist are ignored.
 *
 * Not thread-safe; use one instance per capture thread.
 */
class FftBandAggregator(private val bands: List<Band> = BandLayout.DEFAULT) {
    private var mappedSize = -1
    private var mappedRateHz = Double.NaN
    private var bandOfBin = IntArray(0)

    val bandCount: Int get() = bands.size

    /** Returns the linear power per band for one capture. */
    fun aggregate(fft: ByteArray, samplingRateHz: Double): DoubleArray {
        val out = DoubleArray(bands.size)
        val n = fft.size
        if (n < 4 || samplingRateHz <= 0.0) return out
        val mapping = mappingFor(n, samplingRateHz)
        for (k in 1 until n / 2) {
            val band = mapping[k]
            if (band < 0) continue
            val re = fft[2 * k].toDouble()
            val im = fft[2 * k + 1].toDouble()
            out[band] += re * re + im * im
        }
        return out
    }

    /** Bin index ranges per band for a capture of [captureSize] bytes; empty ranges are possible. */
    fun binRanges(captureSize: Int, samplingRateHz: Double): List<IntRange> {
        val mapping = mappingFor(captureSize, samplingRateHz)
        return bands.indices.map { band ->
            val bins = mapping.indices.filter { mapping[it] == band }
            if (bins.isEmpty()) IntRange.EMPTY else bins.first()..bins.last()
        }
    }

    private fun mappingFor(n: Int, samplingRateHz: Double): IntArray {
        if (n == mappedSize && samplingRateHz == mappedRateHz) return bandOfBin
        val mapping = IntArray(n / 2) { -1 }
        var start = 1 // skip DC
        for ((index, band) in bands.withIndex()) {
            val stop = min(roundBin(band.cutoffHz, n, samplingRateHz), n / 2 - 1)
            val analysisStop = if (band.analysisLimitHz < band.cutoffHz) {
                min(stop, floor(band.analysisLimitHz * n / samplingRateHz).toInt())
            } else {
                stop
            }
            for (k in start..analysisStop) mapping[k] = index
            start = maxOf(start, stop + 1)
        }
        mappedSize = n
        mappedRateHz = samplingRateHz
        bandOfBin = mapping
        return mapping
    }

    private fun roundBin(frequencyHz: Double, n: Int, samplingRateHz: Double): Int =
        (0.5 + frequencyHz * n / samplingRateHz).toInt()
}
