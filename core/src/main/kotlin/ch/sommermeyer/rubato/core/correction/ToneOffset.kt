package ch.sommermeyer.rubato.core.correction

import ch.sommermeyer.rubato.core.analysis.Band
import ch.sommermeyer.rubato.core.analysis.BandLayout

/**
 * Bass / mid / treble offsets in dB, spread over the bands with the weights of the band layout.
 * Used for the global taste offset (bass and treble only) and for the per-song sliders.
 */
data class ToneOffset(
    val bassDb: Double = 0.0,
    val midDb: Double = 0.0,
    val trebleDb: Double = 0.0,
) {
    val isNeutral: Boolean get() = bassDb == 0.0 && midDb == 0.0 && trebleDb == 0.0

    fun toBands(bands: List<Band> = BandLayout.DEFAULT): DoubleArray = DoubleArray(bands.size) {
        val band = bands[it]
        bassDb * band.bassWeight + midDb * band.midWeight + trebleDb * band.trebleWeight
    }

    companion object {
        val NEUTRAL = ToneOffset()

        /**
         * Summarises per-band gains as bass / mid / treble (weighted means), e.g. for the
         * notification text.
         */
        fun summarize(gainsDb: DoubleArray, bands: List<Band> = BandLayout.DEFAULT): ToneOffset {
            require(gainsDb.size == bands.size)
            fun weightedMean(weight: (Band) -> Double): Double {
                var sum = 0.0
                var weights = 0.0
                bands.forEachIndexed { i, band ->
                    sum += weight(band) * gainsDb[i]
                    weights += weight(band)
                }
                return if (weights == 0.0) 0.0 else sum / weights
            }
            return ToneOffset(
                bassDb = weightedMean { it.bassWeight },
                midDb = weightedMean { it.midWeight },
                trebleDb = weightedMean { it.trebleWeight },
            )
        }
    }
}
