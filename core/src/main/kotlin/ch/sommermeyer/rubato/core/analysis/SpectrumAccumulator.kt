package ch.sommermeyer.rubato.core.analysis

/**
 * Averages band powers over many frames.
 *
 * Powers are summed in the linear domain; the conversion to dB and the normalisation happen only
 * when a spectrum is read, so loud and quiet frames are weighted by their actual power.
 */
class SpectrumAccumulator(val bandCount: Int) {
    private val sum = DoubleArray(bandCount)

    var frames: Long = 0
        private set

    /**
     * Adds one frame. If the measurement includes our own EQ, pass the gains that were applied
     * while the frame was captured as [appliedGainDb]; they are divided out per band.
     */
    fun add(bandPower: DoubleArray, appliedGainDb: DoubleArray? = null) {
        require(bandPower.size == bandCount) { "Expected $bandCount bands, got ${bandPower.size}" }
        require(appliedGainDb == null || appliedGainDb.size == bandCount)
        for (b in 0 until bandCount) {
            val gain = appliedGainDb?.get(b) ?: 0.0
            sum[b] += if (gain == 0.0) bandPower[b] else bandPower[b] / Spectra.dbToPowerRatio(gain)
        }
        frames++
    }

    /** Adds a previously stored average, e.g. the analysis of an earlier play of the same song. */
    fun merge(meanPower: DoubleArray, frameCount: Long) {
        require(meanPower.size == bandCount)
        if (frameCount <= 0) return
        for (b in 0 until bandCount) sum[b] += meanPower[b] * frameCount
        frames += frameCount
    }

    fun meanPower(): DoubleArray? =
        if (frames == 0L) null else DoubleArray(bandCount) { sum[it] / frames }

    /** Normalised spectrum in dB (see [Spectra.normalizedDb]), or null without any frames. */
    fun spectrumDb(): DoubleArray? = meanPower()?.let(Spectra::normalizedDb)

    fun reset() {
        sum.fill(0.0)
        frames = 0
    }
}
