package com.shilapi.xcertplay

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Loads what the car can load itself (http, https, data) directly and asks the iPhone for
 * anything else, as Apple's receiver does (unhandledURL): apps can serve custom-scheme playlists and
 * AES-128 keys through their resource loader on the iPhone. FairPlay keys (skd://) are not requested:
 * they need a licensed FairPlay receiver.
 */
@OptIn(UnstableApi::class)
internal class IphoneResolvingDataSource(private val upstream: DataSource) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        if (scheme == null || scheme in DIRECT_SCHEMES) return upstream.open(dataSpec).also { current = upstream }
        if (scheme == "skd") {
            Log.w(TAG, "FairPlay key (skd): needs a licensed FairPlay receiver")
            throw IOException("FairPlay key")
        }
        val resolved = CarPlayVideo.resolveOnIphone(dataSpec.uri.toString())
            ?: throw IOException("iPhone did not load the $scheme URL")
        Log.i(TAG, "iPhone loaded $scheme URL status=${resolved.status} bytes=${resolved.data?.size} redirect=${resolved.location != null}")
        resolved.location?.let { location ->
            return upstream.open(dataSpec.withUri(Uri.parse(location))).also { current = upstream }
        }
        // An app's resource loader answers with data only: status 0 means no HTTP status, not a failure.
        if (resolved.status != null && resolved.status != 0 && resolved.status !in 200..299) throw IOException("iPhone status ${resolved.status}")
        val source = ByteArrayDataSource(resolved.data ?: throw IOException("iPhone sent no data"))
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: throw IOException("not open")

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = IphoneResolvingDataSource(upstream.createDataSource())
    }

    private companion object {
        const val TAG = "DiPlay-Video"
        val DIRECT_SCHEMES = setOf("http", "https", "data", "file", "asset", "content", "rawresource", "android.resource")
    }
}
