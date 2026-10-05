package com.shilapi.xcertplay

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * Optional, for head units whose screen turns: CarPlay gets a square canvas holding a landscape and a
 * portrait screen as view areas, so a turn redraws CarPlay in the other area instead of reconnecting.
 * A square is heavier to encode and decode; the smoother picture caps it at 1920.
 */
object CarPlayRotation {
    private const val TAG = "DiPlay-Rotation"
    private const val PREFS = "diplay_carplay_rotation"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PICTURE = "picture"
    private val SIDES = listOf(2560, 2304, 2048, 1920, 1600, 1280)

    /** How large a square to ask for; sharper is up to the screen's long side. */
    enum class Picture(val maxSide: Int?) { SMOOTHER(1920), SHARPER(null) }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun picture(context: Context): Picture =
        Picture.entries.firstOrNull { it.name == prefs(context).getString(KEY_PICTURE, null) } ?: Picture.SMOOTHER

    fun setPicture(context: Context, picture: Picture) = prefs(context).edit().putString(KEY_PICTURE, picture.name).apply()

    /** The square's side for a screen [longSide] pixels long: the largest a decoder takes, or null. */
    fun squareSide(longSide: Int, picture: Picture, hevc: Boolean): Int? {
        val limit = picture.maxSide?.let { minOf(it, longSide) } ?: longSide
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val decoders = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }.getOrDefault(emptyList())
        val side = (listOf(limit) + SIDES.filter { it < limit }).firstOrNull { candidate ->
            decoders.any { info ->
                runCatching { info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(candidate, candidate) == true }
                    .getOrDefault(false)
            }
        }
        Log.i(TAG, "square ${side ?: "unsupported"} for a $longSide px screen, picture $picture, hevc=$hevc")
        return side?.let { it and 1.inv() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
