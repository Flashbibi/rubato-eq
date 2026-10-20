package ch.sommermeyer.rubato.audio

import android.media.audiofx.Visualizer
import android.os.SystemClock
import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.engine.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * FFT capture of the output mix (audio session 0) with the largest capture size and the highest
 * capture rate the device offers.
 *
 * All methods must be called on the engine thread: the Visualizer delivers its callbacks on the
 * looper of the thread that registered the listener, which is the engine thread as well.
 */
class VisualizerCapture(
    private val log: EventLog,
    private val onFrame: (fft: ByteArray, samplingRateHz: Double, rmsDbfs: Double?) -> Unit,
) {
    enum class State { OFF, RUNNING, ERROR }

    data class Status(
        val state: State = State.OFF,
        val captureSize: Int = 0,
        val captureRateHz: Double = 0.0,
        val samplingRateHz: Double = 0.0,
        val framesPerSecond: Double = 0.0,
        val measurementAvailable: Boolean = false,
        val restarts: Int = 0,
        val lastError: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private var visualizer: Visualizer? = null
    private val peakRms = Visualizer.MeasurementPeakRms()
    private var measurement = false
    private var lastFrameAtMs = 0L
    private var framesInWindow = 0
    private var windowStartMs = 0L
    private var nextAttemptAtMs = 0L
    private var lastErrorLogged: String? = null

    /** Duration of audio represented by one capture, derived from the capture rate. */
    var frameDurationMs: Long = 50
        private set

    val isRunning: Boolean get() = visualizer != null

    private val listener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(visualizer: Visualizer, waveform: ByteArray, samplingRate: Int) = Unit

        override fun onFftDataCapture(visualizer: Visualizer, fft: ByteArray, samplingRate: Int) {
            if (visualizer !== this@VisualizerCapture.visualizer) return
            lastFrameAtMs = SystemClock.elapsedRealtime()
            framesInWindow++
            val rms = if (measurement && visualizer.getMeasurementPeakRms(peakRms) == Visualizer.SUCCESS) {
                peakRms.mRms / 100.0 // millibel -> dBFS
            } else {
                null
            }
            onFrame(fft, samplingRate / 1000.0, rms)
        }
    }

    fun start(nowMs: Long) {
        if (visualizer != null || nowMs < nextAttemptAtMs) return
        var created: Visualizer? = null
        try {
            val v = Visualizer(0)
            created = v
            v.setEnabled(false)
            val size = Visualizer.getCaptureSizeRange()[1]
            v.require(v.setCaptureSize(size), "setCaptureSize($size)")
            v.require(v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED), "setScalingMode")
            measurement = v.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS) == Visualizer.SUCCESS
            val rateMilliHz = Visualizer.getMaxCaptureRate()
            v.require(v.setDataCaptureListener(listener, rateMilliHz, false, true), "setDataCaptureListener")
            v.require(v.setEnabled(true), "setEnabled(true)")

            visualizer = v
            frameDurationMs = (1_000_000L / rateMilliHz.coerceAtLeast(1)).coerceAtLeast(1)
            lastFrameAtMs = nowMs
            windowStartMs = nowMs
            framesInWindow = 0
            lastErrorLogged = null
            _status.update {
                it.copy(
                    state = State.RUNNING,
                    captureSize = v.captureSize,
                    captureRateHz = rateMilliHz / 1000.0,
                    samplingRateHz = v.samplingRate / 1000.0,
                    measurementAvailable = measurement,
                    lastError = null,
                )
            }
            log.info(TAG, R.string.log_visualizer_started, v.captureSize, rateMilliHz / 1000.0, v.samplingRate / 1000)
        } catch (e: Exception) {
            created?.let { runCatching { it.release() } }
            visualizer = null
            nextAttemptAtMs = nowMs + RETRY_DELAY_MS
            val message = e.message ?: e.javaClass.simpleName
            _status.update { it.copy(state = State.ERROR, framesPerSecond = 0.0, lastError = message) }
            if (message != lastErrorLogged) {
                log.error(TAG, R.string.log_visualizer_failed, error = e)
                lastErrorLogged = message
            }
        }
    }

    fun stop() {
        val v = visualizer ?: return
        visualizer = null
        runCatching { v.setEnabled(false) }
        runCatching { v.release() }
        _status.update { it.copy(state = State.OFF, framesPerSecond = 0.0) }
        log.info(TAG, R.string.log_visualizer_stopped)
    }

    /**
     * Updates the frame rate and recreates the Visualizer when it stopped delivering frames although
     * audio is playing (e.g. after an audio server restart or a routing change).
     */
    fun checkHealth(nowMs: Long, expectFrames: Boolean) {
        if (visualizer == null) return
        val window = nowMs - windowStartMs
        if (window >= 1_000) {
            val fps = framesInWindow * 1000.0 / window
            framesInWindow = 0
            windowStartMs = nowMs
            _status.update { it.copy(framesPerSecond = fps) }
        }
        if (expectFrames && nowMs - lastFrameAtMs > STALL_TIMEOUT_MS) {
            log.warn(TAG, R.string.log_visualizer_stalled)
            stop()
            _status.update { it.copy(restarts = it.restarts + 1) }
            nextAttemptAtMs = 0
            start(nowMs)
        }
    }

    private fun Visualizer.require(status: Int, call: String) {
        check(status == Visualizer.SUCCESS) { "$call -> $status" }
    }

    private companion object {
        const val TAG = "visualizer"
        const val RETRY_DELAY_MS = 5_000L
        const val STALL_TIMEOUT_MS = 4_000L
    }
}
