package com.carlink.app.update

import org.json.JSONException
import org.json.JSONObject

/** Where CarLink's releases are published (built locally and uploaded; docs/RELEASING.md). */
object ReleaseRepo {
    const val OWNER_REPO = "F-e-n-y-x/CarLink"
    const val LATEST_URL = "https://api.github.com/repos/$OWNER_REPO/releases/latest"
    const val DOWNLOAD_PREFIX = "https://github.com/$OWNER_REPO/releases/download/"
    const val ACCEPT = "application/vnd.github+json"

    fun apkName(version: AppVersion) = "CarLink-$version.apk"
    fun checksumName(version: AppVersion) = "${apkName(version)}.sha256"
}

/** A published release that carries an installable APK and its checksum. */
data class ReleaseInfo(
    val tag: String,
    val version: AppVersion,
    val title: String,
    /** The release notes (Markdown as written on GitHub). */
    val notes: String,
    val apkUrl: String,
    /** The APK's size in bytes, or 0 when GitHub did not say. */
    val apkSize: Long,
    val checksumUrl: String,
)

/** The result of reading GitHub's "latest release" answer. */
sealed interface ParsedRelease {
    data class Usable(val release: ReleaseInfo) : ParsedRelease
    data class Unusable(val reason: Reason) : ParsedRelease

    enum class Reason { NOT_JSON, DRAFT, PRERELEASE, MALFORMED_TAG, NO_APK, NO_CHECKSUM }
}

/**
 * Parses `GET /repos/{owner}/{repo}/releases/latest`. That endpoint already skips drafts and
 * pre-releases; they are rejected here as well. A release is usable only when its tag is
 * `vX.Y.Z` and it has the assets `CarLink-X.Y.Z.apk` and `CarLink-X.Y.Z.apk.sha256`, both
 * served from this repository's release downloads.
 */
object ReleaseParser {
    fun parse(json: String): ParsedRelease {
        val release = try { JSONObject(json) } catch (_: JSONException) {
            return ParsedRelease.Unusable(ParsedRelease.Reason.NOT_JSON)
        }
        if (release.optBoolean("draft")) return ParsedRelease.Unusable(ParsedRelease.Reason.DRAFT)
        if (release.optBoolean("prerelease")) return ParsedRelease.Unusable(ParsedRelease.Reason.PRERELEASE)
        val tag = release.optString("tag_name")
        val version = AppVersion.parseTag(tag) ?: return ParsedRelease.Unusable(ParsedRelease.Reason.MALFORMED_TAG)
        val assets = release.optJSONArray("assets")
        var apk: Pair<String, Long>? = null
        var checksum: String? = null
        for (i in 0 until (assets?.length() ?: 0)) {
            val asset = assets!!.optJSONObject(i) ?: continue
            val url = asset.optString("browser_download_url")
            if (!url.startsWith(ReleaseRepo.DOWNLOAD_PREFIX)) continue
            when (asset.optString("name")) {
                ReleaseRepo.apkName(version) -> apk = url to asset.optLong("size", 0L)
                ReleaseRepo.checksumName(version) -> checksum = url
            }
        }
        if (apk == null) return ParsedRelease.Unusable(ParsedRelease.Reason.NO_APK)
        if (checksum == null) return ParsedRelease.Unusable(ParsedRelease.Reason.NO_CHECKSUM)
        return ParsedRelease.Usable(ReleaseInfo(
            tag = tag.trim(),
            version = version,
            title = release.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: "CarLink $version",
            notes = release.optString("body").takeIf { it != "null" }.orEmpty(),
            apkUrl = apk.first,
            apkSize = apk.second.coerceAtLeast(0L),
            checksumUrl = checksum,
        ))
    }

    /**
     * The hex SHA-256 from a `.sha256` file: the bare digest, or `sha256sum` output
     * (`<digest>  CarLink-X.Y.Z.apk`). Null when the file holds anything else.
     */
    fun parseChecksum(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
}

/**
 * Release notes as plain text for the update dialog: Markdown marks removed, lists as bullets.
 * Only the part before a line that is exactly [MORE_MARKER] is shown, so the release page can
 * carry install steps, screenshots and checksums below it.
 */
object ReleaseNotes {
    const val MORE_MARKER = "<!-- more -->"

    fun plain(markdown: String, maxChars: Int = 4_000): String {
        val all = markdown.replace("\r\n", "\n").lines()
        val cut = all.indexOfFirst { it.trim() == MORE_MARKER }
        val lines = (if (cut < 0) all else all.take(cut)).map { raw ->
            var line = raw.trimEnd()
            line = line.replace(Regex("""^\s{0,3}#{1,6}\s+"""), "")
            line = line.replace(Regex("""^(\s*)[-*+]\s+"""), "$1• ")
            line = line.replace(Regex("""\*\*(.+?)\*\*"""), "$1").replace(Regex("""__(.+?)__"""), "$1")
            line = line.replace(Regex("""`([^`]*)`"""), "$1")
            line = line.replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
            line
        }
        // No more than one blank line in a row, none at the ends.
        val text = lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        return if (text.length <= maxChars) text else text.take(maxChars).trimEnd() + "…"
    }
}
