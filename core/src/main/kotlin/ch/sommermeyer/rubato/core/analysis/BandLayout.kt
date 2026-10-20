package ch.sommermeyer.rubato.core.analysis

/**
 * One analysis band, which is also one band of the correction EQ.
 *
 * [cutoffHz] is the upper edge of the band. A band spans from the previous band's cutoff to its
 * own, which is exactly how DynamicsProcessing interprets the cutoff of an EqBand.
 *
 * [analysisLimitHz] caps the measurement of a band without changing what the EQ band covers. It is
 * used for the top band: content above ~16 kHz depends mostly on the codec's low-pass filter, not
 * on the mix.
 *
 * The weights describe how strongly the bass / mid / treble controls act on this band.
 */
data class Band(
    val label: String,
    val cutoffHz: Double,
    val analysisLimitHz: Double = cutoffHz,
    val bassWeight: Double = 0.0,
    val midWeight: Double = 0.0,
    val trebleWeight: Double = 0.0,
)

object BandLayout {
    /**
     * Roughly octave-wide bands from ~60 Hz to 16 kHz.
     *
     * The Visualizer delivers at most a 1024-point capture, i.e. bins of ~43 Hz (44.1 kHz) or
     * ~47 Hz (48 kHz). An octave band around 63 Hz would contain a single bin, so the two lowest
     * octaves are merged into one band (bins at ~47, 94, 141 and 188 Hz). Above that the band
     * edges follow the standard octave centres 250 Hz ... 16 kHz.
     */
    val DEFAULT: List<Band> = listOf(
        Band("90", cutoffHz = 177.0, bassWeight = 1.0),
        Band("250", cutoffHz = 354.0, bassWeight = 0.6, midWeight = 0.3),
        Band("500", cutoffHz = 707.0, bassWeight = 0.2, midWeight = 0.8),
        Band("1k", cutoffHz = 1414.0, midWeight = 1.0),
        Band("2k", cutoffHz = 2828.0, midWeight = 0.8, trebleWeight = 0.2),
        Band("4k", cutoffHz = 5657.0, midWeight = 0.3, trebleWeight = 0.6),
        Band("8k", cutoffHz = 11314.0, trebleWeight = 1.0),
        // The EQ band reaches up to Nyquist (48 kHz output), the measurement stops at 16 kHz.
        Band("16k", cutoffHz = 24000.0, analysisLimitHz = 16000.0, trebleWeight = 1.0),
    )

    val SIZE: Int = DEFAULT.size
}
