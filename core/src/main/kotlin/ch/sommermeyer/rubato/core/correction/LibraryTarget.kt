package ch.sommermeyer.rubato.core.correction

/**
 * Running average of the normalised spectra of all analysed songs: the target curve.
 *
 * Every song counts once. When a song that is already part of the average is analysed again, its
 * previous contribution is replaced instead of being added a second time.
 */
class LibraryTarget(val bandCount: Int) {
    private val sum = DoubleArray(bandCount)

    var songCount: Int = 0
        private set

    fun add(spectrumDb: DoubleArray) {
        require(spectrumDb.size == bandCount)
        for (b in 0 until bandCount) sum[b] += spectrumDb[b]
        songCount++
    }

    fun replace(previousDb: DoubleArray, updatedDb: DoubleArray) {
        require(previousDb.size == bandCount && updatedDb.size == bandCount)
        check(songCount > 0) { "replace() on an empty library" }
        for (b in 0 until bandCount) sum[b] += updatedDb[b] - previousDb[b]
    }

    fun clear() {
        sum.fill(0.0)
        songCount = 0
    }

    /** Average spectrum, or null while no song has been analysed. */
    fun mean(): DoubleArray? =
        if (songCount == 0) null else DoubleArray(bandCount) { sum[it] / songCount }

    /** Share of the configured strength that is applied for the current library size (n/20). */
    fun confidence(): Double = confidence(songCount)

    companion object {
        const val FULL_CONFIDENCE_SONGS = 20

        fun confidence(songCount: Int): Double =
            (songCount.toDouble() / FULL_CONFIDENCE_SONGS).coerceIn(0.0, 1.0)

        fun of(bandCount: Int, spectra: Iterable<DoubleArray>): LibraryTarget =
            LibraryTarget(bandCount).apply { spectra.forEach(::add) }
    }
}
