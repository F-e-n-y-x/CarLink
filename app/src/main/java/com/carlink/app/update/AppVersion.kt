package com.carlink.app.update

/**
 * A CarLink release version, `major.minor.patch`.
 *
 * Its Android versionCode is `major * 10000 + minor * 100 + patch` (1.0.0 → 10000, 1.2.3 →
 * 10203), so minor and patch stay below 100 (app/build.gradle.kts, docs/RELEASING.md).
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {

    val versionCode: Int get() = major * 10_000 + minor * 100 + patch

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    /** The release tag on GitHub, `vX.Y.Z`. */
    val tag: String get() = "v$this"

    companion object {
        private val TAG = Regex("""[vV]?(\d{1,4})\.(\d{1,2})\.(\d{1,2})""")
        private val NAME = Regex("""(\d{1,4})\.(\d{1,2})\.(\d{1,2})(?:-.*)?""")

        /**
         * A release tag (`v1.2.3` or `1.2.3`), or null when it is anything else: pre-release
         * suffixes, four parts, words. Malformed tags are never offered as updates.
         */
        fun parseTag(tag: String?): AppVersion? = tag?.trim()?.let(TAG::matchEntire)?.toVersion()

        /** The installed versionName; a build suffix (`1.0.0-hud-test`) is ignored. */
        fun parseVersionName(name: String?): AppVersion? = name?.trim()?.let(NAME::matchEntire)?.toVersion()

        private fun MatchResult.toVersion(): AppVersion? {
            val (major, minor, patch) = destructured
            return runCatching { AppVersion(major.toInt(), minor.toInt(), patch.toInt()) }.getOrNull()
        }
    }
}
