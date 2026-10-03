package com.shilapi.xcertplay.media

import kotlin.math.abs

/** Fits the negotiated CarPlay canvas inside the current window without changing its aspect ratio. */
data class CarPlayVideoLayout(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= left + width && y >= top && y <= top + height

    companion object {
        const val ASPECT_TOLERANCE = 0.04

        fun fit(canvasWidth: Int, canvasHeight: Int, viewWidth: Int, viewHeight: Int): CarPlayVideoLayout {
            if (canvasWidth <= 0 || canvasHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
                return CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            }
            val viewAspect = viewWidth.toDouble() / viewHeight
            val canvasAspect = canvasWidth.toDouble() / canvasHeight
            val aspectDifference = abs(viewAspect - canvasAspect) / canvasAspect
            if (aspectDifference <= ASPECT_TOLERANCE) {
                return CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            }
            val scale = minOf(viewWidth.toFloat() / canvasWidth, viewHeight.toFloat() / canvasHeight)
            val width = canvasWidth * scale
            val height = canvasHeight * scale
            return CarPlayVideoLayout((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
        }
    }
}

