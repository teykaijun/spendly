package com.spendly.update

import org.json.JSONObject

/**
 * What the update feed says the newest build is.
 *
 * Parsing lives here as pure Kotlin (org.json is in the framework, so no
 * dependency and no Android context) which makes the awkward part — reading
 * someone else's JSON shape — unit testable.
 */
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val notes: String,
    /** Optional lowercase hex SHA-256 of the APK, when the feed publishes one. */
    val sha256: String? = null,
) {
    /** Updates must move forwards. Equal or lower build numbers are not offers. */
    fun isNewerThan(currentVersionCode: Long): Boolean = versionCode > currentVersionCode
}

/**
 * Where to look for new builds.
 *
 * Two shapes are supported because the app is sideloaded rather than on Play:
 * a GitHub repository (the usual home for this kind of thing), or any URL
 * serving a small JSON file, for self-hosting.
 */
sealed interface UpdateSource {

    val describedAs: String

    /** Resolves to the GitHub Releases API for the latest published release. */
    data class GitHubReleases(val owner: String, val repo: String) : UpdateSource {
        override val describedAs: String get() = "github.com/$owner/$repo"
        val apiUrl: String get() = "https://api.github.com/repos/$owner/$repo/releases/latest"
    }

    /** A JSON document you host yourself, in the shape documented in the README. */
    data class JsonUrl(val url: String) : UpdateSource {
        override val describedAs: String get() = url
    }

    companion object {
        /**
         * Reads the single string the user types into Settings.
         *
         * "owner/repo" is treated as GitHub; anything starting with https:// is
         * treated as a JSON feed. Plain http is rejected rather than silently
         * downgraded — an APK fetched over cleartext is an arbitrary code
         * execution path.
         */
        fun parse(raw: String): UpdateSource? {
            val value = raw.trim()
            if (value.isEmpty()) return null

            if (value.startsWith("http://", ignoreCase = true)) return null

            if (value.startsWith("https://", ignoreCase = true)) {
                // A pasted GitHub web URL is still a GitHub repo.
                val gh = Regex("^https://(?:www\\.)?github\\.com/([^/\\s]+)/([^/\\s]+)")
                    .find(value)
                if (gh != null) {
                    return GitHubReleases(gh.groupValues[1], gh.groupValues[2].removeSuffix(".git"))
                }
                return JsonUrl(value)
            }

            val parts = value.split('/')
            if (parts.size == 2 && parts.all { it.isNotBlank() && !it.contains(' ') }) {
                return GitHubReleases(parts[0], parts[1].removeSuffix(".git"))
            }
            return null
        }
    }
}

object UpdateManifestParser {

    /**
     * Reads a GitHub "latest release" payload.
     *
     * The version comes from the tag (`v1.4.2` or `1.4.2`). GitHub has no notion
     * of an Android versionCode, so it is derived from the tag's numeric parts —
     * which means tags must be ordered sensibly, and that is documented.
     */
    fun fromGitHubRelease(json: String): UpdateManifest? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optBoolean("draft", false)) return null
        if (root.optBoolean("prerelease", false)) return null

        val tag = root.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val versionName = tag.removePrefix("v").removePrefix("V")
        val versionCode = versionCodeFromName(versionName) ?: return null

        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
            if (!url.startsWith("https://", ignoreCase = true)) continue

            return UpdateManifest(
                versionCode = versionCode,
                versionName = versionName,
                apkUrl = url,
                sizeBytes = asset.optLong("size", 0L),
                notes = root.optString("body").trim(),
            )
        }
        return null
    }

    /** Reads the self-hosted shape, which states the versionCode outright. */
    fun fromJsonFeed(json: String): UpdateManifest? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val versionCode = root.optLong("versionCode", -1L).takeIf { it > 0 } ?: return null
        val apkUrl = root.optString("apkUrl").takeIf { it.startsWith("https://", true) } ?: return null
        return UpdateManifest(
            versionCode = versionCode,
            versionName = root.optString("versionName").ifBlank { versionCode.toString() },
            apkUrl = apkUrl,
            sizeBytes = root.optLong("sizeBytes", 0L),
            notes = root.optString("notes").trim(),
            sha256 = root.optString("sha256").lowercase().takeIf { it.length == 64 },
        )
    }

    /**
     * "1.4.2" -> 10402, "2.0" -> 20000, "1.4.2.9" -> 10402 (extra parts ignored).
     *
     * Two digits per component, so every part must stay under 100. That is a real
     * constraint on tag names and is called out in the README; the alternative is
     * a feed format that states versionCode explicitly, which the JSON shape does.
     */
    fun versionCodeFromName(versionName: String): Long? {
        val parts = versionName.trim().split('.', '-', '+')
            .mapNotNull { segment -> segment.takeWhile { it.isDigit() }.toIntOrNull() }
        if (parts.isEmpty()) return null
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        if (major > 9999 || minor > 99 || patch > 99) return null
        return major * 10_000L + minor * 100L + patch
    }
}
