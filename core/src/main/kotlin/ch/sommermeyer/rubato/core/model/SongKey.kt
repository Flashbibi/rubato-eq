package ch.sommermeyer.rubato.core.model

import java.util.Locale

/**
 * Identifies a song across plays: artist + title + duration (in whole seconds).
 * Case and whitespace differences are ignored.
 */
@JvmInline
value class SongKey(val value: String) {
    companion object {
        fun of(artist: String?, title: String?, durationMs: Long?): SongKey? {
            val normalizedTitle = normalize(title)
            if (normalizedTitle.isEmpty()) return null
            val seconds = durationMs?.takeIf { it > 0 }?.div(1000) ?: 0
            return SongKey("${normalize(artist)}|$normalizedTitle|$seconds")
        }

        private val whitespace = Regex("\\s+")

        private fun normalize(text: String?): String =
            text.orEmpty().trim().replace(whitespace, " ").lowercase(Locale.ROOT)
    }
}
