package ch.sommermeyer.rubato.core.analysis

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

object Spectra {
    /** Power floor that keeps empty bands finite in dB (about -120 dB). */
    const val POWER_FLOOR = 1e-12

    fun powerToDb(power: Double): Double = 10.0 * log10(max(power, POWER_FLOOR))

    fun dbToPowerRatio(db: Double): Double = 10.0.pow(db / 10.0)

    /**
     * Converts linear band powers to dB relative to the overall level of the spectrum.
     *
     * The overall level is the mean of the band levels (in dB). Subtracting it removes loudness
     * and leaves only the tonal balance; it also makes `target - measured` zero-mean, so the
     * automatic correction does not change the overall level on average.
     */
    fun normalizedDb(power: DoubleArray): DoubleArray {
        val db = DoubleArray(power.size) { powerToDb(power[it]) }
        val mean = db.average()
        return DoubleArray(db.size) { db[it] - mean }
    }
}
