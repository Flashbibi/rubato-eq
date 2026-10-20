package ch.sommermeyer.rubato.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.SystemClock
import ch.sommermeyer.rubato.R
import ch.sommermeyer.rubato.core.analysis.Band
import ch.sommermeyer.rubato.core.analysis.BandLayout
import ch.sommermeyer.rubato.core.correction.CorrectionMath
import ch.sommermeyer.rubato.core.correction.GainRamp
import ch.sommermeyer.rubato.data.EqMode
import ch.sommermeyer.rubato.engine.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Applies the correction with DynamicsProcessing (pre-EQ, one band per analysis band), either on
 * the output mix or on every player session that was announced by broadcast.
 *
 * Gains never jump: target changes are ramped ([GainRamp]); new effects fade in from 0 dB, removed
 * ones fade out first. Confined to the engine thread ([scope] must run on it).
 */
class EqEffectController(
    private val scope: CoroutineScope,
    private val log: EventLog,
    private val bands: List<Band> = BandLayout.DEFAULT,
) {
    enum class AttachmentState { ACTIVE, DISABLED, NO_CONTROL, ERROR }

    data class AttachmentStatus(
        val sessionId: Int,
        val packageName: String?,
        val state: AttachmentState,
        val detail: String?,
    )

    data class Status(
        val mode: EqMode? = null,
        val bypassed: Boolean = false,
        val attachments: List<AttachmentStatus> = emptyList(),
        val lastError: String? = null,
    ) {
        val active: Boolean get() = attachments.any { it.state == AttachmentState.ACTIVE }
    }

    private class Attachment(val sessionId: Int, val packageName: String?, bandCount: Int) {
        var effect: DynamicsProcessing? = null
        val sent = DoubleArray(bandCount) { Double.NaN }
        var fade = 0.0
        var fadeTarget = 1.0
        var removeWhenSilent = false
        var hasControl = true
        var enabled = false
        var error: String? = null

        val isActive: Boolean get() = effect != null && error == null && hasControl && enabled
    }

    private val ramp = GainRamp(bands.size)
    private val attachments = linkedMapOf<Int, Attachment>()
    private var current = DoubleArray(bands.size)
    private var mode: EqMode? = null
    private var pendingMode: EqMode? = null
    private var bypassed = false
    private var sessions: Map<Int, PlayerSession> = emptyMap()
    private var loopJob: Job? = null
    private var retryJob: Job? = null
    private var lastError: String? = null
    private var lastLoggedAttachError: String? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Gains that currently reach the listener through our EQ (0 dB if no effect is active). */
    fun appliedGainsDb(): DoubleArray {
        val fade = attachments.values.filter { it.isActive }.maxOfOrNull { it.fade } ?: 0.0
        return DoubleArray(bands.size) { current[it] * fade }
    }

    fun setMode(newMode: EqMode) {
        if (newMode == mode && pendingMode == null) return
        if (attachments.isEmpty()) {
            mode = newMode
            pendingMode = null
            attachForMode()
        } else {
            // Fade the old effects out first; running both at once would apply the EQ twice.
            pendingMode = newMode
            attachments.values.forEach { it.fadeTarget = 0.0; it.removeWhenSilent = true }
            ensureLoop()
        }
        publish()
    }

    fun onSessionsChanged(newSessions: Map<Int, PlayerSession>) {
        sessions = newSessions
        if (mode == EqMode.SESSION && pendingMode == null) syncSessions()
    }

    fun setTarget(gainsDb: DoubleArray) {
        val limited = CorrectionMath.clamp(gainsDb, CorrectionMath.HARD_LIMIT_DB)
        ramp.retarget(limited, now())
        ensureLoop()
    }

    /** Pause: fade the EQ out and disable the effects; resume: enable and fade back in. */
    fun setBypassed(value: Boolean) {
        if (bypassed == value) return
        bypassed = value
        attachments.values.forEach { a ->
            if (a.removeWhenSilent) return@forEach
            if (!value) setEffectEnabled(a, true)
            a.fadeTarget = if (value) 0.0 else 1.0
        }
        ensureLoop()
        publish()
    }

    /** Fades out (if [fade]) and releases all effects. */
    suspend fun shutdown(fade: Boolean) {
        retryJob?.cancel()
        pendingMode = null
        if (fade && attachments.values.any { it.isActive }) {
            attachments.values.forEach { it.fadeTarget = 0.0; it.removeWhenSilent = true }
            ensureLoop()
            withTimeoutOrNull(FADE_MS + 1_000) {
                while (attachments.isNotEmpty()) delay(STEP_MS)
            }
        }
        attachments.values.toList().forEach(::release)
        loopJob?.cancel()
        mode = null
        publish()
    }

    private fun attachForMode() {
        when (mode) {
            EqMode.GLOBAL -> attach(GLOBAL_SESSION, null)
            EqMode.SESSION -> syncSessions()
            null -> Unit
        }
    }

    private fun syncSessions() {
        attachments.values.filter { it.sessionId !in sessions }.forEach(::release)
        sessions.values.filter { it.sessionId !in attachments }.forEach { attach(it.sessionId, it.packageName) }
        publish()
    }

    private fun attach(sessionId: Int, packageName: String?) {
        val a = Attachment(sessionId, packageName, bands.size)
        attachments[sessionId] = a
        try {
            val effect = DynamicsProcessing(PRIORITY, sessionId, buildConfig())
            a.effect = effect
            effect.setControlStatusListener { _, granted ->
                scope.launch { onControlChanged(a, granted) }
            }
            effect.setEnableStatusListener { _, enabled ->
                scope.launch {
                    a.enabled = enabled
                    publish()
                }
            }
            a.hasControl = effect.hasControl()
            setEffectEnabled(a, !bypassed)
            a.fade = 0.0
            a.fadeTarget = if (bypassed) 0.0 else 1.0
            lastLoggedAttachError = null
            if (sessionId == GLOBAL_SESSION) {
                log.info(TAG, R.string.log_effect_attached_global)
            } else {
                log.info(TAG, R.string.log_effect_attached_session, sessionId, packageName.orEmpty())
            }
            if (!a.hasControl) log.warn(TAG, R.string.log_effect_no_control, sessionId)
        } catch (e: Exception) {
            a.effect?.let { runCatching { it.release() } }
            a.effect = null
            val message = e.message ?: e.javaClass.simpleName
            // Retries repeat the same failure every few seconds; log it once.
            if (message != lastLoggedAttachError) log.error(TAG, R.string.log_effect_attach_failed, sessionId, error = e)
            lastLoggedAttachError = message
            a.error = message
            lastError = message
            if (sessionId == GLOBAL_SESSION) scheduleRetry()
        }
        ensureLoop()
        publish()
    }

    private fun setEffectEnabled(a: Attachment, enabled: Boolean) {
        val effect = a.effect ?: return
        val result = runCatching { effect.setEnabled(enabled) }.getOrElse { AudioEffect.ERROR }
        if (result == AudioEffect.SUCCESS) {
            a.enabled = enabled
        } else if (enabled) {
            a.error = "setEnabled(true) -> $result"
            lastError = a.error
            log.error(TAG, R.string.log_effect_enable_failed, a.sessionId, result)
        }
    }

    private fun onControlChanged(a: Attachment, granted: Boolean) {
        if (attachments[a.sessionId] !== a) return
        a.hasControl = granted
        if (granted) {
            a.sent.fill(Double.NaN) // the other app may have changed the bands
            log.info(TAG, R.string.log_effect_control_regained, a.sessionId)
            ensureLoop()
        } else {
            log.warn(TAG, R.string.log_effect_no_control, a.sessionId)
        }
        publish()
    }

    private fun release(a: Attachment) {
        a.effect?.let { effect ->
            runCatching { effect.setEnabled(false) }
            runCatching { effect.release() }
        }
        a.effect = null
        attachments.remove(a.sessionId)
    }

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            var last = now()
            while (isActive) {
                val t = now()
                val elapsed = (t - last).coerceAtLeast(0)
                last = t
                current = ramp.valueAt(t)
                var busy = !ramp.isSettled(t)
                for (a in attachments.values.toList()) {
                    busy = advanceFade(a, elapsed) || busy
                    push(a)
                    if (a.fade == 0.0 && a.fadeTarget == 0.0) {
                        if (a.removeWhenSilent) release(a) else if (bypassed && a.enabled) setEffectEnabled(a, false)
                    }
                }
                if (pendingMode != null && attachments.isEmpty()) {
                    mode = pendingMode
                    pendingMode = null
                    log.info(TAG, R.string.log_effect_mode, mode!!.name)
                    attachForMode()
                    busy = true
                }
                publish()
                if (!busy) break
                delay(STEP_MS)
            }
        }
    }

    /** Returns true while the fade is still moving. */
    private fun advanceFade(a: Attachment, elapsedMs: Long): Boolean {
        val step = elapsedMs.toDouble() / FADE_MS
        a.fade = when {
            a.fade < a.fadeTarget -> min(a.fadeTarget, a.fade + step)
            a.fade > a.fadeTarget -> max(a.fadeTarget, a.fade - step)
            else -> a.fade
        }
        return a.fade != a.fadeTarget
    }

    private fun push(a: Attachment) {
        val effect = a.effect ?: return
        if (a.error != null || !a.hasControl || !a.enabled) return
        for (b in bands.indices) {
            val gain = current[b] * a.fade
            if (!a.sent[b].isNaN() && abs(a.sent[b] - gain) < MIN_CHANGE_DB) continue
            try {
                effect.setPreEqBandAllChannelsTo(b, DynamicsProcessing.EqBand(true, bands[b].cutoffHz.toFloat(), gain.toFloat()))
                a.sent[b] = gain
            } catch (e: Exception) {
                onPushFailed(a, e)
                return
            }
        }
    }

    private fun onPushFailed(a: Attachment, e: Exception) {
        val control = runCatching { a.effect?.hasControl() }.getOrNull()
        if (control == false) {
            a.hasControl = false
            log.warn(TAG, R.string.log_effect_no_control, a.sessionId)
            return
        }
        a.error = e.message ?: e.javaClass.simpleName
        lastError = a.error
        log.error(TAG, R.string.log_effect_update_failed, a.sessionId, error = e)
        a.effect?.let { runCatching { it.release() } }
        a.effect = null
        if (a.sessionId == GLOBAL_SESSION) scheduleRetry()
    }

    /** Keeps re-attaching to the output mix while it fails (e.g. after an audio server restart). */
    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (isActive) {
                delay(RETRY_DELAY_MS)
                val failed = attachments[GLOBAL_SESSION]
                if (mode != EqMode.GLOBAL || pendingMode != null || failed == null || failed.effect != null) break
                attachments.remove(GLOBAL_SESSION)
                attach(GLOBAL_SESSION, null)
                if (attachments[GLOBAL_SESSION]?.effect != null) break
            }
        }
    }

    private fun buildConfig(): DynamicsProcessing.Config {
        val builder = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            true, bands.size, // pre-EQ
            false, 0, // multi-band compressor
            false, 0, // post-EQ
            true, // limiter
        )
        // 20 ms -> 1024-point FFT at 44.1/48 kHz, the same bin grid as the analysis.
        builder.setPreferredFrameDuration(FRAME_DURATION_MS)
        val eq = DynamicsProcessing.Eq(true, true, bands.size)
        bands.forEachIndexed { i, band -> eq.setBand(i, DynamicsProcessing.EqBand(true, band.cutoffHz.toFloat(), 0f)) }
        builder.setPreEqAllChannelsTo(eq)
        builder.setInputGainAllChannelsTo(0f)
        // Safety net against overs caused by boosts; transparent below 0 dBFS.
        builder.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, 0f, 0f),
        )
        return builder.build()
    }

    private fun publish() {
        _status.value = Status(
            mode = pendingMode ?: mode,
            bypassed = bypassed,
            attachments = attachments.values.map { a ->
                val state = when {
                    a.error != null || a.effect == null -> AttachmentState.ERROR
                    !a.hasControl -> AttachmentState.NO_CONTROL
                    !a.enabled -> AttachmentState.DISABLED
                    else -> AttachmentState.ACTIVE
                }
                AttachmentStatus(a.sessionId, a.packageName, state, a.error)
            },
            lastError = lastError,
        )
    }

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        const val GLOBAL_SESSION = 0
        private const val TAG = "effect"
        private const val PRIORITY = Int.MAX_VALUE
        private const val CHANNELS = 2
        private const val FRAME_DURATION_MS = 20f
        private const val STEP_MS = 40L
        private const val FADE_MS = GainRamp.DEFAULT_DURATION_MS
        private const val MIN_CHANGE_DB = 0.01
        private const val RETRY_DELAY_MS = 10_000L
    }
}
