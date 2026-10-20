package ch.sommermeyer.rubato.core.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitHubReleaseTest {
    private val sample = """
        {
          "url": "https://api.github.com/repos/Flashbibi/rubato-eq/releases/1",
          "html_url": "https://github.com/Flashbibi/rubato-eq/releases/tag/v0.1.7",
          "id": 1,
          "tag_name": "v0.1.7",
          "name": "v0.1.7",
          "draft": false,
          "prerelease": false,
          "published_at": "2026-10-20T12:00:00Z",
          "author": { "login": "Flashbibi" },
          "assets": [
            {
              "name": "other.txt",
              "size": 3,
              "browser_download_url": "https://github.com/Flashbibi/rubato-eq/releases/download/v0.1.7/other.txt"
            },
            {
              "name": "rubato-eq.apk",
              "size": 12345678,
              "content_type": "application/vnd.android.package-archive",
              "browser_download_url": "https://github.com/Flashbibi/rubato-eq/releases/download/v0.1.7/rubato-eq.apk"
            }
          ],
          "body": "- Fix ramp\n- Add export"
        }
    """.trimIndent()

    @Test
    fun `parses the fields the updater needs and ignores the rest`() {
        val release = GitHubRelease.parse(sample)

        assertEquals("v0.1.7", release.tagName)
        assertEquals("- Fix ramp\n- Add export", release.body)
        val apk = release.asset()!!
        assertEquals(12_345_678, apk.size)
        assertEquals("https://github.com/Flashbibi/rubato-eq/releases/download/v0.1.7/rubato-eq.apk", apk.downloadUrl)
    }

    @Test
    fun `missing apk asset is reported as null`() {
        val release = GitHubRelease.parse("""{"tag_name": "v0.1.8", "assets": []}""")
        assertNull(release.asset())
        assertNull(release.body)
    }
}
