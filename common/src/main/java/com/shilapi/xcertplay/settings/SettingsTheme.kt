package com.shilapi.xcertplay.settings

import android.content.Context
import android.graphics.Color

enum class SettingsTheme(
    val isOverlay: Boolean,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val accentTrack: Int,
    val trackOff: Int,
    val buttonText: Int,
) {
    CARD(
        isOverlay = false,
        textPrimary = Color.rgb(241, 245, 252),
        textSecondary = Color.rgb(168, 182, 202),
        accent = Color.rgb(166, 200, 255),
        accentTrack = Color.rgb(0x32, 0x58, 0x8c),
        trackOff = Color.rgb(42, 56, 75),
        buttonText = Color.rgb(12, 17, 27),
    ),
    OVERLAY(
        isOverlay = true,
        textPrimary = Color.WHITE,
        textSecondary = Color.rgb(0xcc, 0xd3, 0xdf),
        accent = Color.rgb(0x6f, 0x9f, 0xd9),
        accentTrack = Color.rgb(0x32, 0x58, 0x8c),
        trackOff = Color.rgb(0x3b, 0x3d, 0x47),
        buttonText = Color.rgb(0x12, 0x13, 0x18),
    );

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
