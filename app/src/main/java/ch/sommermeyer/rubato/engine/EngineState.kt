package ch.sommermeyer.rubato.engine

import ch.sommermeyer.rubato.audio.EqEffectController
import ch.sommermeyer.rubato.audio.VisualizerCapture
import ch.sommermeyer.rubato.core.analysis.BandLayout
import ch.sommermeyer.rubato.core.correction.ToneOffset
import ch.sommermeyer.rubato.nowplaying.NowPlaying

data class SongState(
    val key: String,
    val title: String,
    val artist: String,
    /** A stored correction was found and applied from the start. */
    val known: Boolean,
    /** The database lookup for this song has finished. */
    val loaded: Boolean,
    val sessionAnalyzedMs: Long,
    val totalAnalyzedMs: Long,
    val hasCorrection: Boolean,
    val spectrumDb: List<Double>?,
    val autoCorrectionDb: List<Double>?,
    val manual: ToneOffset,
    val inLibrary: Boolean,
)

/** Snapshot of the engine for UI and notification. */
data class EngineState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val notificationAccess: Boolean = true,
    val nowPlaying: NowPlaying? = null,
    val song: SongState? = null,
    val liveSpectrumDb: List<Double>? = null,
    val targetDb: List<Double>? = null,
    val appliedGainsDb: List<Double> = List(BandLayout.SIZE) { 0.0 },
    val librarySongs: Int = 0,
    val effectiveStrength: Double = 0.0,
    val compensation: Boolean = false,
    val visualizer: VisualizerCapture.Status = VisualizerCapture.Status(),
    val effect: EqEffectController.Status = EqEffectController.Status(),
)
