package ch.sommermeyer.rubato.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Subset of the GitHub "latest release" response that the updater needs. */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
) {
    fun asset(name: String = APK_ASSET_NAME): GitHubAsset? = assets.firstOrNull { it.name == name }

    companion object {
        const val APK_ASSET_NAME = "rubato-eq.apk"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): GitHubRelease = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)
