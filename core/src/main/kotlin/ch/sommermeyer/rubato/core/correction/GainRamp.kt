package ch.sommermeyer.rubato.core.correction

import kotlin.math.abs

/**
 * Per-band linear ramp in dB. A new target never makes the output jump: the ramp restarts from
 * the value at the moment of the change and reaches the target after [durationMs].
 */
class GainRamp(val bandCount: Int, val durationMs: Long = DEFAULT_DURATION_MS) {
    private var from = DoubleArray(bandCount)
    private var to = DoubleArray(bandCount)
    private var startMs = 0L

    val target: DoubleArray get() = to.copyOf()

    fun retarget(target: DoubleArray, nowMs: Long) {
        require(target.size == bandCount)
        if (sameAs(target, to)) return
        from = valueAt(nowMs)
        to = target.copyOf()
        startMs = nowMs
    }

    /** Sets the value immediately, without ramping (e.g. for a freshly created effect at 0 dB). */
    fun reset(value: DoubleArray, nowMs: Long) {
        require(value.size == bandCount)
        from = value.copyOf()
        to = value.copyOf()
        startMs = nowMs - durationMs
    }

    fun valueAt(nowMs: Long): DoubleArray {
        val progress = progressAt(nowMs)
        return DoubleArray(bandCount) { from[it] + (to[it] - from[it]) * progress }
    }

    fun isSettled(nowMs: Long): Boolean = progressAt(nowMs) >= 1.0

    private fun progressAt(nowMs: Long): Double {
        if (durationMs <= 0) return 1.0
        return ((nowMs - startMs).toDouble() / durationMs).coerceIn(0.0, 1.0)
    }

    private fun sameAs(a: DoubleArray, b: DoubleArray): Boolean =
        a.indices.all { abs(a[it] - b[it]) < EPSILON_DB }

    companion object {
        const val DEFAULT_DURATION_MS = 1_500L
        private const val EPSILON_DB = 1e-4
    }
}
