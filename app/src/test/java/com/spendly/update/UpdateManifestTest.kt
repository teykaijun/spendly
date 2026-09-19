package com.spendly.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {

    // ---------- reading what the user typed ----------

    @Test
    fun `owner slash repo is treated as github`() {
        val source = UpdateSource.parse("teykaijun/spendly")
        assertTrue(source is UpdateSource.GitHubReleases)
        source as UpdateSource.GitHubReleases
        assertEquals("teykaijun", source.owner)
        assertEquals("spendly", source.repo)
        assertEquals(
            "https://api.github.com/repos/teykaijun/spendly/releases/latest",
            source.apiUrl,
        )
    }

    @Test
    fun `a pasted github web url is still a github repo`() {
        val source = UpdateSource.parse("https://github.com/teykaijun/spendly")
        assertTrue(source is UpdateSource.GitHubReleases)
        assertEquals("spendly", (source as UpdateSource.GitHubReleases).repo)
    }

    @Test
    fun `a git suffix is stripped`() {
        val source = UpdateSource.parse("https://github.com/teykaijun/spendly.git")
        assertEquals("spendly", (source as UpdateSource.GitHubReleases).repo)
    }

    @Test
    fun `an https url is treated as a json feed`() {
        val source = UpdateSource.parse("https://example.com/spendly/update.json")
        assertTrue(source is UpdateSource.JsonUrl)
    }

    @Test
    fun `cleartext http is refused outright`() {
        // The payload of this request gets installed, so downgrading is not an option.
        assertNull(UpdateSource.parse("http://example.com/update.json"))
    }

    @Test
    fun `nonsense is rejected`() {
        assertNull(UpdateSource.parse(""))
        assertNull(UpdateSource.parse("   "))
        assertNull(UpdateSource.parse("just-a-word"))
        assertNull(UpdateSource.parse("too/many/parts"))
    }

    // ---------- version codes ----------

    @Test
    fun `version names become ordered version codes`() {
        assertEquals(10402L, UpdateManifestParser.versionCodeFromName("1.4.2"))
        assertEquals(20000L, UpdateManifestParser.versionCodeFromName("2.0"))
        assertEquals(10000L, UpdateManifestParser.versionCodeFromName("1"))
        assertEquals(10402L, UpdateManifestParser.versionCodeFromName("1.4.2.9"))
    }

    @Test
    fun `version ordering is monotonic where it matters`() {
        val v102 = UpdateManifestParser.versionCodeFromName("1.0.2")!!
        val v110 = UpdateManifestParser.versionCodeFromName("1.1.0")!!
        val v200 = UpdateManifestParser.versionCodeFromName("2.0.0")!!
        assertTrue(v102 < v110)
        assertTrue(v110 < v200)
        // The classic trap: 1.10 must outrank 1.9, not lose a string comparison.
        val v19 = UpdateManifestParser.versionCodeFromName("1.9")!!
        val v110b = UpdateManifestParser.versionCodeFromName("1.10")!!
        assertTrue(v110b > v19)
    }

    @Test
    fun `components that would overflow their two digits are refused`() {
        assertNull(UpdateManifestParser.versionCodeFromName("1.100.0"))
        assertNull(UpdateManifestParser.versionCodeFromName("1.0.100"))
        assertNull(UpdateManifestParser.versionCodeFromName("not-a-version"))
    }

    // ---------- github release payloads ----------

    private fun githubJson(
        tag: String = "v1.1.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assetName: String = "spendly-1.1.0.apk",
        assetUrl: String = "https://github.com/o/r/releases/download/v1.1.0/spendly.apk",
    ) = """
        {
          "tag_name": "$tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "body": "Fixed the thing",
          "assets": [
            {"name": "$assetName", "size": 1234567, "browser_download_url": "$assetUrl"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses a github release`() {
        val m = UpdateManifestParser.fromGitHubRelease(githubJson())
        assertNotNull(m)
        assertEquals("1.1.0", m!!.versionName)
        assertEquals(10100L, m.versionCode)
        assertEquals(1234567L, m.sizeBytes)
        assertEquals("Fixed the thing", m.notes)
    }

    @Test
    fun `drafts and prereleases are not offered`() {
        assertNull(UpdateManifestParser.fromGitHubRelease(githubJson(draft = true)))
        assertNull(UpdateManifestParser.fromGitHubRelease(githubJson(prerelease = true)))
    }

    @Test
    fun `a release with no apk asset is not an update`() {
        assertNull(
            UpdateManifestParser.fromGitHubRelease(
                githubJson(assetName = "notes.txt", assetUrl = "https://example.com/notes.txt"),
            ),
        )
    }

    @Test
    fun `an apk served over http is not accepted`() {
        assertNull(
            UpdateManifestParser.fromGitHubRelease(
                githubJson(assetUrl = "http://example.com/spendly.apk"),
            ),
        )
    }

    @Test
    fun `malformed json does not throw`() {
        assertNull(UpdateManifestParser.fromGitHubRelease("not json at all"))
        assertNull(UpdateManifestParser.fromGitHubRelease(""))
        assertNull(UpdateManifestParser.fromJsonFeed("{"))
    }

    // ---------- self-hosted feed ----------

    @Test
    fun `parses a self hosted feed`() {
        val json = """
            {
              "versionCode": 12,
              "versionName": "1.2.0",
              "apkUrl": "https://example.com/spendly-1.2.0.apk",
              "sizeBytes": 2048,
              "notes": "Nice things",
              "sha256": "${"a".repeat(64)}"
            }
        """.trimIndent()
        val m = UpdateManifestParser.fromJsonFeed(json)
        assertNotNull(m)
        assertEquals(12L, m!!.versionCode)
        assertEquals("1.2.0", m.versionName)
        assertEquals("a".repeat(64), m.sha256)
    }

    @Test
    fun `a feed without an https apk url is rejected`() {
        val json = """{"versionCode": 12, "apkUrl": "http://example.com/x.apk"}"""
        assertNull(UpdateManifestParser.fromJsonFeed(json))
    }

    @Test
    fun `a malformed sha is ignored rather than trusted`() {
        val json = """
            {"versionCode": 3, "apkUrl": "https://e.com/a.apk", "sha256": "abc"}
        """.trimIndent()
        assertNull(UpdateManifestParser.fromJsonFeed(json)!!.sha256)
    }

    // ---------- the comparison the button depends on ----------

    @Test
    fun `only strictly newer builds are offered`() {
        val m = UpdateManifest(10100L, "1.1.0", "https://e.com/a.apk", 0, "")
        assertTrue(m.isNewerThan(10000L))
        assertFalse(m.isNewerThan(10100L))
        assertFalse(m.isNewerThan(10200L))
    }
}
