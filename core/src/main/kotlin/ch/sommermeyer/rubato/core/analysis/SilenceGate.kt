package ch.sommermeyer.rubato.core.analysis

/**
 * Decides whether a captured frame carries enough signal to be analysed.
 *
 * The Visualizer normalises each capture to its peak, so near-silence (dither, noise floor) would
 * look like a full-scale signal in the FFT. The RMS of the unscaled input, as reported by the
 * Visualizer's peak/RMS measurement, is therefore the primary criterion when it is available.
 */
object SilenceGate {
    /** RMS below this level (dBFS) counts as silence. */
    const val DEFAULT_RMS_THRESHOLD_DB = -70.0

    /** Total FFT power below this value counts as silence (an all-zero capture is the norm). */
    const val MIN_TOTAL_POWER = 1.0

    fun isSilent(
        bandPower: DoubleArray,
        rmsDbfs: Double?,
        rmsThresholdDb: Double = DEFAULT_RMS_THRESHOLD_DB,
    ): Boolean {
        if (bandPower.sum() < MIN_TOTAL_POWER) return true
        return rmsDbfs != null && rmsDbfs < rmsThresholdDb
    }
}
