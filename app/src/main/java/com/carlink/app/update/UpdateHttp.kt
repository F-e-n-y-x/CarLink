package com.carlink.app.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A text answer; [rateLimited] when GitHub refused because of its request limit (403/429). */
data class HttpText(val code: Int, val body: String, val rateLimited: Boolean = false)

/** The download was cancelled by the driver. */
class DownloadCancelledException : IOException("download cancelled")

/** The network side of the updater, behind an interface so the logic is testable without one. */
interface UpdateHttp {
    /** GET [url] as text. Throws [IOException] when there is no answer at all. */
    fun getText(url: String, accept: String? = null): HttpText

    /**
     * Downloads [url] into [destination], reporting (bytes so far, total or null). Throws
     * [IOException] on failure and [DownloadCancelledException] when [isCancelled] turns true;
     * a partial file is deleted either way.
     */
    fun download(url: String, destination: File, isCancelled: () -> Boolean, onProgress: (Long, Long?) -> Unit)
}

/** [UpdateHttp] on [HttpURLConnection]: timeouts, size limits, no extra dependencies. */
class UrlConnectionHttp(private val userAgent: String) : UpdateHttp {

    override fun getText(url: String, accept: String?): HttpText {
        val connection = open(url)
        try {
            accept?.let { connection.setRequestProperty("Accept", it) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { readLimited(it) }.orEmpty()
            val rateLimited = code == 429 || (code == 403 && connection.getHeaderField("X-RateLimit-Remaining") == "0")
            return HttpText(code, body, rateLimited)
        } finally {
            connection.disconnect()
        }
    }

    override fun download(url: String, destination: File, isCancelled: () -> Boolean, onProgress: (Long, Long?) -> Unit) {
        destination.parentFile?.mkdirs()
        val connection = open(url)
        connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MILLIS
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("download failed: HTTP $code")
            val total = connection.contentLengthLong.takeIf { it > 0 }
            if (total != null && total > MAX_APK_BYTES) throw IOException("download too large: $total bytes")
            var written = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw DownloadCancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written > MAX_APK_BYTES) throw IOException("download too large")
                        onProgress(written, total)
                    }
                    output.fd.sync()
                }
            }
            if (total != null && written != total) throw IOException("incomplete download: $written of $total bytes")
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "updates use HTTPS only" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            useCaches = false
            // GitHub's release downloads redirect to its asset host (HTTPS to HTTPS).
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
        }
    }

    private fun readLimited(stream: InputStream): String {
        val bytes = stream.readNBytesCompat(MAX_TEXT_BYTES + 1)
        if (bytes.size > MAX_TEXT_BYTES) throw IOException("answer too large")
        return String(bytes, Charsets.UTF_8)
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 15_000
        const val DOWNLOAD_READ_TIMEOUT_MILLIS = 30_000
        const val MAX_TEXT_BYTES = 1024 * 1024
        const val MAX_APK_BYTES = 512L * 1024 * 1024
    }
}

/** What a check found. */
sealed interface CheckOutcome {
    data class Newer(val release: ReleaseInfo) : CheckOutcome
    /** Nothing newer: the latest release is this version or older, unusable, or there is none yet. */
    data object UpToDate : CheckOutcome
    data object RateLimited : CheckOutcome
    data class Failed(val detail: String) : CheckOutcome
}

/** Asks GitHub for the latest release. */
fun interface ReleaseSource {
    /** Blocking; call off the main thread. */
    fun latest(): CheckOutcome
}

/** [ReleaseSource] on GitHub's `releases/latest`, compared against the installed [current] version. */
class GitHubReleaseSource(private val http: UpdateHttp, private val current: AppVersion) : ReleaseSource {
    override fun latest(): CheckOutcome {
        val answer = try { http.getText(ReleaseRepo.LATEST_URL, ReleaseRepo.ACCEPT) } catch (e: IOException) {
            return CheckOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        return when {
            answer.rateLimited -> CheckOutcome.RateLimited
            // No published release yet.
            answer.code == 404 -> CheckOutcome.UpToDate
            answer.code !in 200..299 -> CheckOutcome.Failed("HTTP ${answer.code}")
            else -> when (val parsed = ReleaseParser.parse(answer.body)) {
                is ParsedRelease.Unusable -> CheckOutcome.UpToDate
                is ParsedRelease.Usable ->
                    if (parsed.release.version > current) CheckOutcome.Newer(parsed.release) else CheckOutcome.UpToDate
            }
        }
    }
}
