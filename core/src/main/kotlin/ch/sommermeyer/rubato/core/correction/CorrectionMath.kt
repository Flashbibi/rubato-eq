package ch.sommermeyer.rubato.core.correction

import kotlin.math.abs

object CorrectionMath {
    /** Default and UI limits of the automatic correction (dB per band). */
    const val DEFAULT_MAX_DB = 3.0
    const val MAX_ALLOWED_DB = 6.0

    /** Safety limit for automatic + manual correction combined. */
    const val HARD_LIMIT_DB = 12.0

    /**
     * Smooths across neighbouring bands with a [1/4, 1/2, 1/4] kernel. At the edges the kernel is
     * renormalised ([2/3, 1/3]), so a constant curve stays constant.
     */
    fun smooth(values: DoubleArray): DoubleArray {
        val n = values.size
        if (n < 2) return values.copyOf()
        return DoubleArray(n) { i ->
            var weighted = 0.5 * values[i]
            var weights = 0.5
            if (i > 0) {
                weighted += 0.25 * values[i - 1]
                weights += 0.25
            }
            if (i < n - 1) {
                weighted += 0.25 * values[i + 1]
                weights += 0.25
            }
            weighted / weights
        }
    }

    fun clamp(values: DoubleArray, limitDb: Double): DoubleArray {
        val limit = abs(limitDb)
        return DoubleArray(values.size) { values[it].coerceIn(-limit, limit) }
    }

    /**
     * Automatic correction: `strength * (target - measured)`, smoothed across neighbouring bands
     * and limited to ±[maxDb].
     */
    fun autoCorrection(
        target: DoubleArray,
        measured: DoubleArray,
        strength: Double,
        maxDb: Double,
    ): DoubleArray {
        require(target.size == measured.size) { "target and measurement differ in size" }
        val s = strength.coerceIn(0.0, 1.0)
        val raw = DoubleArray(target.size) { s * (target[it] - measured[it]) }
        return clamp(smooth(raw), maxDb)
    }

    /** Moves [current] towards [desired], at most [maxStepDb] per band. */
    fun limitStep(current: DoubleArray, desired: DoubleArray, maxStepDb: Double): DoubleArray {
        require(current.size == desired.size)
        val step = abs(maxStepDb)
        return DoubleArray(current.size) {
            current[it] + (desired[it] - current[it]).coerceIn(-step, step)
        }
    }

    fun add(a: DoubleArray, b: DoubleArray): DoubleArray {
        require(a.size == b.size)
        return DoubleArray(a.size) { a[it] + b[it] }
    }
}
