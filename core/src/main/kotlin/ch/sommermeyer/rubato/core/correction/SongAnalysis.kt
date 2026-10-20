package ch.sommermeyer.rubato.core.correction

import ch.sommermeyer.rubato.core.analysis.Band
import ch.sommermeyer.rubato.core.analysis.BandLayout
import ch.sommermeyer.rubato.core.analysis.SpectrumAccumulator

data class CorrectionTiming(
    /** Analysed audio before the first correction. */
    val firstCorrectionAfterMs: Long = 5_000,
    /** Analysed audio between two follow-up corrections. */
    val updateIntervalMs: Long = 10_000,
    /** Maximum change per band and follow-up correction. */
    val maxStepDb: Double = 0.5,
    /** Frames right after a song change are discarded; they may still contain the previous track. */
    val settleAfterStartMs: Long = 1_000,
    /** Analysed audio before a song is added to the library average. */
    val libraryMinAnalyzedMs: Long = 20_000,
)

/** Inputs for the automatic correction of one song. */
class CorrectionParams(
    /** Target curve (library average + taste offset); null while no song has been analysed. */
    val target: DoubleArray?,
    /** Configured strength, already scaled with min(1, n/20). */
    val effectiveStrength: Double,
    val maxDb: Double,
) {
    companion object {
        fun from(
            library: LibraryTarget,
            strength: Double,
            maxDb: Double,
            taste: ToneOffset,
            bands: List<Band> = BandLayout.DEFAULT,
        ): CorrectionParams {
            val target = library.mean()?.let { CorrectionMath.add(it, taste.toBands(bands)) }
            return CorrectionParams(target, strength.coerceIn(0.0, 1.0) * library.confidence(), maxDb)
        }
    }
}

/** An earlier analysis of the same song, e.g. loaded from the database. */
class StoredAnalysis(
    val meanPower: DoubleArray,
    val frameCount: Long,
    val analyzedMs: Long,
    val autoCorrectionDb: DoubleArray?,
)

/**
 * Measurement of the song that is currently playing and the schedule of its automatic correction.
 *
 * The schedule runs on analysed audio (playing and not silent), so pauses and silence delay it:
 * - the first correction is due after [CorrectionTiming.firstCorrectionAfterMs] and is applied in
 *   full,
 * - afterwards the correction is re-evaluated every [CorrectionTiming.updateIntervalMs] and moves
 *   by at most [CorrectionTiming.maxStepDb] per band.
 * A song with a stored analysis starts with its stored correction and keeps refining the stored
 * measurement.
 */
class SongAnalysis(
    val bandCount: Int = BandLayout.SIZE,
    private val timing: CorrectionTiming = CorrectionTiming(),
) {
    private val accumulator = SpectrumAccumulator(bandCount)
    private var nextEvaluationAtMs = timing.firstCorrectionAfterMs

    /** Audio analysed during this play. */
    var sessionAnalyzedMs: Long = 0
        private set

    /** Audio analysed in total, including restored analyses. */
    var totalAnalyzedMs: Long = 0
        private set

    /** Automatic correction currently in effect; null until the first correction. */
    var currentCorrection: DoubleArray? = null
        private set

    val frameCount: Long get() = accumulator.frames

    val hasEnoughData: Boolean get() = totalAnalyzedMs >= timing.firstCorrectionAfterMs

    val isLibraryReady: Boolean get() = totalAnalyzedMs >= timing.libraryMinAnalyzedMs

    fun meanPower(): DoubleArray? = accumulator.meanPower()

    fun spectrumDb(): DoubleArray? = accumulator.spectrumDb()

    fun restore(stored: StoredAnalysis) {
        accumulator.merge(stored.meanPower, stored.frameCount)
        totalAnalyzedMs += stored.analyzedMs
        if (currentCorrection == null) currentCorrection = stored.autoCorrectionDb?.copyOf()
    }

    /**
     * Adds a non-silent frame captured [msSinceStart] after the song started. [appliedGainDb] is the
     * EQ that was active while the frame was captured, if it has to be removed from the measurement.
     * Returns false if the frame was discarded.
     */
    fun addFrame(
        bandPower: DoubleArray,
        frameDurationMs: Long,
        msSinceStart: Long,
        appliedGainDb: DoubleArray? = null,
    ): Boolean {
        if (msSinceStart < timing.settleAfterStartMs) return false
        accumulator.add(bandPower, appliedGainDb)
        sessionAnalyzedMs += frameDurationMs
        totalAnalyzedMs += frameDurationMs
        return true
    }

    /**
     * Returns a new correction if one is due, otherwise null. [force] recomputes right away and
     * without step limit, e.g. after the user changed strength, limit or taste.
     */
    fun evaluate(params: CorrectionParams, force: Boolean = false): DoubleArray? {
        if (!hasEnoughData) return null
        val previous = currentCorrection
        val firstDue = previous == null
        val periodicDue = previous != null && sessionAnalyzedMs >= nextEvaluationAtMs
        if (!firstDue && !periodicDue && !force) return null

        if (firstDue || force) {
            nextEvaluationAtMs = sessionAnalyzedMs + timing.updateIntervalMs
        } else {
            while (nextEvaluationAtMs <= sessionAnalyzedMs) nextEvaluationAtMs += timing.updateIntervalMs
        }

        val measured = accumulator.spectrumDb() ?: return null
        val desired = params.target
            ?.let { CorrectionMath.autoCorrection(it, measured, params.effectiveStrength, params.maxDb) }
            ?: DoubleArray(bandCount)
        val next = if (previous == null || force) {
            desired
        } else {
            CorrectionMath.limitStep(previous, desired, timing.maxStepDb)
        }
        currentCorrection = next
        return next.copyOf()
    }
}
