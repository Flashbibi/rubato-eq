package ch.sommermeyer.rubato.core.update

/**
 * Dotted numeric version such as `0.1.42`. A leading `v` (release tags) and any suffix after `-`
 * or `+` (e.g. `-debug`, `-local`) are ignored. Missing trailing parts count as 0.
 */
class AppVersion private constructor(val parts: List<Int>) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        val length = maxOf(parts.size, other.parts.size)
        for (i in 0 until length) {
            val diff = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is AppVersion && compareTo(other) == 0

    override fun hashCode(): Int = parts.dropLastWhile { it == 0 }.hashCode()

    override fun toString(): String = parts.joinToString(".")

    companion object {
        fun parse(text: String?): AppVersion? {
            val core = text?.trim()
                ?.removePrefix("v")?.removePrefix("V")
                ?.substringBefore('-')?.substringBefore('+')
                ?: return null
            if (core.isEmpty()) return null
            val parts = core.split('.').map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
            return AppVersion(parts)
        }

        /** True if [latestTag] denotes a newer version than [currentVersionName]. */
        fun isNewer(latestTag: String?, currentVersionName: String?): Boolean {
            val latest = parse(latestTag) ?: return false
            val current = parse(currentVersionName) ?: return true
            return latest > current
        }
    }
}
