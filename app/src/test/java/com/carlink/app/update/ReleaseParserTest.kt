package com.carlink.app.update

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** org.json is Android's, so these run on Robolectric. Fixtures: src/test/resources/update/. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ReleaseParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader!!.getResource("update/$name")) { "missing fixture $name" }.readText()

    private fun unusable(name: String): ParsedRelease.Reason? =
        (ReleaseParser.parse(fixture(name)) as? ParsedRelease.Unusable)?.reason

    @Test fun parsesAUsableRelease() {
        val release = (ReleaseParser.parse(fixture("release_ok.json")) as ParsedRelease.Usable).release
        assertEquals("v1.1.0", release.tag)
        assertEquals(AppVersion(1, 1, 0), release.version)
        assertEquals("CarLink 1.1.0", release.title)
        assertEquals("https://github.com/F-e-n-y-x/CarLink/releases/download/v1.1.0/CarLink-1.1.0.apk", release.apkUrl)
        assertEquals("https://github.com/F-e-n-y-x/CarLink/releases/download/v1.1.0/CarLink-1.1.0.apk.sha256", release.checksumUrl)
        assertEquals(48_234_567L, release.apkSize)
        assertTrue(release.notes.contains("Faster"))
    }

    @Test fun releaseWithoutApkIsUnusable() = assertEquals(ParsedRelease.Reason.NO_APK, unusable("release_no_apk.json"))

    @Test fun releaseWithoutChecksumIsUnusable() = assertEquals(ParsedRelease.Reason.NO_CHECKSUM, unusable("release_no_sha.json"))

    @Test fun preReleaseIsUnusable() = assertEquals(ParsedRelease.Reason.PRERELEASE, unusable("release_prerelease.json"))

    @Test fun malformedTagIsIgnored() = assertEquals(ParsedRelease.Reason.MALFORMED_TAG, unusable("release_malformed_tag.json"))

    @Test fun assetsFromAnotherRepositoryAreIgnored() =
        assertEquals(ParsedRelease.Reason.NO_APK, unusable("release_foreign_assets.json"))

    @Test fun draftAndGarbageAreUnusable() {
        assertEquals(ParsedRelease.Reason.DRAFT,
            (ReleaseParser.parse(fixture("release_ok.json").replace("\"draft\": false", "\"draft\": true")) as ParsedRelease.Unusable).reason)
        assertEquals(ParsedRelease.Reason.NOT_JSON, (ReleaseParser.parse("<html>rate limited</html>") as ParsedRelease.Unusable).reason)
        assertEquals(ParsedRelease.Reason.MALFORMED_TAG, (ReleaseParser.parse("{}") as ParsedRelease.Unusable).reason)
    }

    @Test fun parsesChecksumFiles() {
        val hex = "a".repeat(64)
        assertEquals(hex, ReleaseParser.parseChecksum(hex))
        assertEquals(hex, ReleaseParser.parseChecksum("${hex.uppercase()}  CarLink-1.1.0.apk\n"))
        assertNull(ReleaseParser.parseChecksum("not a digest"))
        assertNull(ReleaseParser.parseChecksum("abc123"))
        assertNull(ReleaseParser.parseChecksum(""))
    }

    @Test fun releaseNotesBecomePlainText() {
        val notes = ReleaseNotes.plain((ReleaseParser.parse(fixture("release_ok.json")) as ParsedRelease.Usable).release.notes)
        assertEquals("What's new\n\n• Faster wireless CarPlay\n• Fixed Android Auto audio\n\nSee the changelog.", notes)
        assertTrue(ReleaseNotes.plain("x".repeat(5000)).endsWith("…"))
    }

    @Test fun popupStopsAtTheMoreMarker() {
        val body = "## What's new\r\n\r\n- Faster CarPlay\r\n\r\n<!-- more -->\r\n\r\n## Install\r\n![shot](x.webp)"
        assertEquals("What's new\n\n• Faster CarPlay", ReleaseNotes.plain(body))
        // An indented marker counts; text that merely mentions it does not cut.
        assertEquals("a", ReleaseNotes.plain("a\n  <!-- more -->  \nb"))
        assertEquals("a <!-- more --> b", ReleaseNotes.plain("a <!-- more --> b"))
    }

    @Test fun gitHubSourceAnswers() {
        val current = AppVersion(1, 0, 0)
        fun source(answer: HttpText) = GitHubReleaseSource(FakeHttp(answer), current).latest()
        val ok = fixture("release_ok.json")
        assertTrue(source(HttpText(200, ok)) is CheckOutcome.Newer)
        assertEquals(CheckOutcome.UpToDate, GitHubReleaseSource(FakeHttp(HttpText(200, ok)), AppVersion(1, 1, 0)).latest())
        assertEquals(CheckOutcome.UpToDate, source(HttpText(200, fixture("release_no_apk.json"))))
        assertEquals(CheckOutcome.UpToDate, source(HttpText(404, "{\"message\":\"Not Found\"}")))
        assertEquals(CheckOutcome.RateLimited, source(HttpText(403, "{}", rateLimited = true)))
        assertEquals(CheckOutcome.RateLimited, source(HttpText(429, "", rateLimited = true)))
        assertTrue(source(HttpText(500, "")) is CheckOutcome.Failed)
        assertTrue(GitHubReleaseSource(FakeHttp(null), current).latest() is CheckOutcome.Failed)
    }

    private class FakeHttp(private val answer: HttpText?) : UpdateHttp {
        override fun getText(url: String, accept: String?): HttpText {
            assertEquals(ReleaseRepo.LATEST_URL, url)
            assertEquals("application/vnd.github+json", accept)
            return answer ?: throw java.io.IOException("offline")
        }
        override fun download(url: String, destination: File, isCancelled: () -> Boolean, onProgress: (Long, Long?) -> Unit) =
            error("not used")
    }
}
